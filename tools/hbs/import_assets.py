#!/usr/bin/env python3
"""Convert locally installed HBS/CAB chassis into a private Gaea model cache.

No Unity runtime or game logic is loaded. Only the base prefab's intact geometry
and diffuse textures are read. Gaea supplies gameplay and animation at runtime.
"""

import argparse
import gc
import hashlib
import json
import re
import sys
from pathlib import Path

import numpy as np
import UnityPy
from PIL import Image
from UnityPy.export.MeshRendererExporter import get_mesh
from UnityPy.helpers.MeshHelper import MeshHandler

VERSION = 1
# Unity is left handed, Y up, +Z forward. Gaea is right handed, Z up, +Y forward.
BASIS = np.array([[1., 0., 0.], [0., 0., 1.], [0., 1., 0.]])
# HBS meters to the existing Gaea authored-model scale (roughly four units/metre).
MODEL_SCALE = 4.0
JOINTS = {
    "root": ("root", None, ("j_Root",)),
    "hips": ("pelvis", "root", ("j_Pelvis",)),
    "torso": ("CT", "pelvis", ("j_Spine2", "j_Spine1", "j_Spine")),
    "head": ("HD", "CT", ("j_Head",)),
    "leftArm": ("LA", "LT", ("j_LUpperArm", "j_LShoulder")),
    "leftForearm": ("LA-forearm", "LA", ("j_LForearm",)),
    "rightArm": ("RA", "RT", ("j_RUpperArm", "j_RShoulder")),
    "rightForearm": ("RA-forearm", "RA", ("j_RForearm",)),
    "leftLeg": ("LL", "pelvis", ("j_LHip", "j_LThigh")),
    "leftShin": ("LL-shin", "LL", ("j_LCalf", "j_LKnee")),
    "leftFoot": ("LL-foot", "LL-shin", ("j_LFoot",)),
    "rightLeg": ("RL", "pelvis", ("j_RHip", "j_RThigh")),
    "rightShin": ("RL-shin", "RL", ("j_RCalf", "j_RKnee")),
    "rightFoot": ("RL-foot", "RL-shin", ("j_RFoot",)),
}


def key(name):
    return re.sub(r"[^a-z0-9]", "", name.lower())


def matrix(transform):
    p, q, s = transform.m_LocalPosition, transform.m_LocalRotation, transform.m_LocalScale
    x, y, z, w = q.x, q.y, q.z, q.w
    result = np.eye(4)
    result[:3, :3] = np.array([
        [1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
        [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
        [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]]) @ np.diag([s.x, s.y, s.z])
    result[:3, 3] = [p.x, p.y, p.z]
    return result


def transform_points(points, transform):
    return points @ transform[:3, :3].T + transform[:3, 3]


def intact(path):
    """These branches are visual alternatives, even when Unity marks them active."""
    return not re.search(r"(?i)(?:_dmg|_destroyed|_damage|_explode|blip|vfx|shadow|simgame|collider)", path)


def prefab_scene(root):
    transforms, names, renderers, excluded_lods = {}, {}, [], set()

    def walk(go, parent, path):
        components = [c.component.read() for c in go.m_Component if c.component]
        transform = next(c for c in components if type(c).__name__ == "Transform")
        world = parent @ matrix(transform)
        transforms[transform.object_reader.path_id] = world
        names.setdefault(go.m_Name.lower(), world)
        path += "/" + go.m_Name
        for component in components:
            kind = type(component).__name__
            if kind in ("SkinnedMeshRenderer", "MeshRenderer") and intact(path) and component.m_Enabled:
                # Base prefabs contain sensor icons and helper meshes outside their chassis subtree.
                if kind == "SkinnedMeshRenderer" or "/mesh" in path.lower():
                    renderers.append((component, world, path))
            if kind == "LODGroup":
                for lod in component.m_LODs[1:]:
                    excluded_lods.update(r.renderer.m_PathID for r in lod.renderers)
        for child in transform.m_Children:
            walk(child.read().m_GameObject.read(), world, path)

    walk(root, np.eye(4), "")
    return transforms, names, [r for r in renderers if r[0].object_reader.path_id not in excluded_lods]


def geometry(renderer, world, transforms):
    mesh = get_mesh(renderer)
    if mesh is None:
        return None
    # UnityPy 1.24.2's legacy 2017 decoder slices the version; use its tuple API.
    handler = MeshHandler(mesh, version=mesh.object_reader.version.as_tuple())
    handler.process()
    if not handler.m_Vertices:
        return None
    faces_by_part = []
    for part in handler.get_triangles():
        faces = np.asarray(part, dtype=int).reshape(-1, 3)
        # Some CAB prefabs disable an obsolete part with a single zero-area triangle and no skin weights.
        faces = faces[(faces[:, 0] != faces[:, 1]) & (faces[:, 1] != faces[:, 2]) & (faces[:, 0] != faces[:, 2])]
        faces_by_part.append(faces)
    if not any(len(faces) for faces in faces_by_part):
        return None
    positions = np.asarray(handler.m_Vertices, dtype=float)[:, :3]
    normals = np.asarray(handler.m_Normals, dtype=float)[:, :3] if handler.m_Normals else np.zeros_like(positions)
    bones = getattr(renderer, "m_Bones", None)
    if bones and mesh.m_BindPose and handler.m_BoneWeights:
        weights = np.asarray(handler.m_BoneWeights)
        indices = np.asarray(handler.m_BoneIndices, dtype=int)
        vertices, directions = np.zeros_like(positions), np.zeros_like(normals)
        total = weights.sum(axis=1)
        if np.any(total < .99) or np.any(total > 1.01):
            raise ValueError("Invalid skin weights")
        for index, (bone, bind) in enumerate(zip(bones, mesh.m_BindPose)):
            weight = np.where(indices == index, weights, 0).sum(axis=1)
            if not np.any(weight):
                continue
            bind_matrix = np.array([[getattr(bind, f"e{r}{c}") for c in range(4)] for r in range(4)])
            skin = transforms[bone.m_PathID] @ bind_matrix
            vertices += transform_points(positions, skin) * weight[:, None]
            directions += (normals @ np.linalg.inv(skin[:3, :3])) * weight[:, None]
        positions, normals = vertices, directions
    else:
        positions = transform_points(positions, world)
        normals = normals @ np.linalg.inv(world[:3, :3])
    positions = positions @ BASIS.T * MODEL_SCALE
    normals = normals @ BASIS.T
    lengths = np.linalg.norm(normals, axis=1)
    normals /= np.maximum(lengths[:, None], 1e-10)
    uv = np.asarray(handler.m_UV0, dtype=float)[:, :2] if handler.m_UV0 else np.zeros((len(positions), 2))
    uv[:, 1] = 1 - uv[:, 1]
    if not np.isfinite(positions).all() or not np.isfinite(normals).all() or not np.isfinite(uv).all():
        raise ValueError("Non-finite mesh coordinates")
    triangles = []
    for faces in faces_by_part:
        if len(faces):
            cross = np.cross(positions[faces[:, 1]] - positions[faces[:, 0]],
                             positions[faces[:, 2]] - positions[faces[:, 0]])
            # Converted Unity meshes differ in handedness; keep the winding facing their outward normals.
            if np.sum(cross * normals[faces].mean(axis=1)) < 0:
                faces = faces[:, ::-1]
        triangles.append(faces)
    return positions, normals, uv, triangles


def location(path):
    """Use prefab body locations, never a guessed unit/loadout name."""
    value = key(path)
    if "pelvis" in value:
        return "pelvis"
    for token, node in (("meshhead", "HD"), ("meshlarm", "LA"), ("meshrarm", "RA"),
                        ("meshlleg", "LL"), ("meshrleg", "RL"), ("meshltorso", "LT"),
                        ("meshrtorso", "RT"), ("meshctorso", "CT"),
                        ("leftarm", "LA"), ("rightarm", "RA"), ("leftleg", "LL"),
                        ("rightleg", "RL"), ("lefttorso", "LT"), ("righttorso", "RT"), ("head", "HD")):
        if token in value:
            if node in ("LA", "RA") and any(s in value for s in ("forearm", "hand")):
                return node + "-forearm"
            if node in ("LL", "RL"):
                if any(s in value for s in ("foot", "toe", "talon", "pinky", "index")):
                    return node + "-foot"
                if "calf" in value or "shin" in value:
                    return node + "-shin"
            return node
    return "CT"


def split_mesh(vertices, indices, limit=30000):
    """Keep every triangle while respecting libGDX's signed-short mesh builders."""
    remap, chosen, output = {}, [], []
    for triangle in indices:
        if len(remap) + sum(int(i) not in remap for i in triangle) > limit and output:
            yield vertices[chosen], output
            remap, chosen, output = {}, [], []
        for index in triangle:
            index = int(index)
            if index not in remap:
                remap[index] = len(chosen)
                chosen.append(index)
            output.append(remap[index])
    if output:
        yield vertices[chosen], output


def material(pointer, output, materials, textures):
    identity = str(pointer.m_PathID)
    if identity in materials:
        return identity
    source = pointer.read()
    props = source.m_SavedProperties
    colors = dict(props.m_Colors)
    color = colors.get("_Color")
    value = {"id": identity, "diffuse": [color.r, color.g, color.b] if color else [1, 1, 1],
             "specular": [.15, .15, .15], "shininess": 20}
    main = dict(props.m_TexEnvs).get("_MainTex")
    if main and main.m_Texture:
        texture_id = str(main.m_Texture.m_PathID)
        if texture_id not in textures:
            texture = main.m_Texture.read()
            pixels = texture.image.convert("RGB")
            pixels.thumbnail((1024, 1024), Image.Resampling.LANCZOS)
            pixels.save(output / (texture_id + ".png"))
            textures.add(texture_id)
        value["textures"] = [{"id": texture_id, "filename": texture_id + ".png", "type": "DIFFUSE",
                              "uvTranslation": [main.m_Offset.x, -main.m_Offset.y],
                              "uvScaling": [main.m_Scale.x, main.m_Scale.y]}]
    materials[identity] = value
    return identity


def convert(bundle, output):
    env = UnityPy.load(str(bundle))
    candidates = sorted((p, o) for p, o in env.container.items()
                        if Path(p).stem.lower() == bundle.name.lower() and o.type.name == "GameObject")
    if not candidates:
        raise ValueError("No base chassis prefab")
    transforms, names, renderers = prefab_scene(candidates[0][1].read())
    pieces = []
    for renderer, world, path in renderers:
        data = geometry(renderer, world, transforms)
        if data is not None and data[3] and renderer.m_Materials:
            pieces.append((renderer, location(path), data))
    if not pieces:
        raise ValueError("No intact chassis geometry")
    all_points = np.concatenate([p[2][0] for p in pieces])
    low, high = all_points.min(axis=0), all_points.max(axis=0)
    if high[2] - low[2] < 4 or high[2] - low[2] > 250:
        raise ValueError("Chassis bounds are outside the supported scale")
    offset = np.array([(low[0] + high[0]) / 2, (low[1] + high[1]) / 2, low[2]])
    positions, parents, roles = {}, {}, {}
    for role, (node, parent, aliases) in JOINTS.items():
        source = next((names[a.lower()] for a in aliases if a.lower() in names), None)
        if source is not None:
            positions[node] = BASIS @ source[:3, 3] * MODEL_SCALE - offset
            parents[node] = parent
            roles[role] = node
    # Unknown rigs remain playable static chassis. Never drive a quadruped with a biped gait.
    rigged = all(role in roles for role in ("hips", "torso", "leftLeg", "rightLeg", "leftShin", "rightShin",
                                          "leftFoot", "rightFoot"))
    positions.setdefault("root", np.zeros(3))
    parents["root"] = None
    roles["root"] = "root"
    for node in ("pelvis", "CT", "LT", "RT", "HD", "LA", "RA", "LL", "RL",
                 "LA-forearm", "RA-forearm", "LL-shin", "RL-shin", "LL-foot", "RL-foot"):
        if node not in positions:
            parent = {"pelvis": "root", "CT": "pelvis", "LT": "CT", "RT": "CT",
                      "HD": "CT", "LA": "LT", "RA": "RT", "LL": "pelvis", "RL": "pelvis"}.get(node)
            if parent is None:
                parent = node[:2] + "-shin" if node.endswith("-foot") else node[:2]
            parents[node] = parent
            positions[node] = positions[parent].copy()
    nodes = {node: {"id": node, "translation": np.round(position - positions.get(parents[node], np.zeros(3)), 6).tolist(),
                    "parts": [], "children": []} for node, position in positions.items()}
    for node, parent in parents.items():
        if parent:
            nodes[parent]["children"].append(nodes[node])
    materials, textures, meshes = {}, set(), []
    for renderer, node, (points, normals, uv, parts) in pieces:
        vertices = np.concatenate([points - offset - positions[node], normals, uv], axis=1)
        for index, triangles in enumerate(parts):
            if not len(triangles):
                continue
            pointer = renderer.m_Materials[min(index, len(renderer.m_Materials) - 1)]
            if not pointer:
                raise ValueError("Chassis material is missing")
            mat = material(pointer, output, materials, textures)
            for section, indices in split_mesh(vertices, triangles):
                identity = "mesh" + str(len(meshes))
                meshes.append({"attributes": ["POSITION", "NORMAL", "TEXCOORD0"],
                               "vertices": np.round(section, 6).reshape(-1).tolist(),
                               "parts": [{"id": identity, "type": "TRIANGLES", "indices": indices}]})
                nodes[node]["parts"].append({"meshpartid": identity, "materialid": mat})
    model = {"version": [0, 1], "id": bundle.name, "meshes": meshes,
             "materials": list(materials.values()), "nodes": [nodes["root"]], "animations": []}
    write_json(output / "model.g3dj", model)
    descriptor = {"schema": VERSION, "mesh": "model.g3dj", "joints": roles if rigged else {},
                  "upperBodyNode": "CT", "triangles": sum(len(m["parts"][0]["indices"]) // 3 for m in meshes),
                  "textures": sorted(t + ".png" for t in textures)}
    write_json(output / "model.json", descriptor)
    return descriptor


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, separators=(",", ":"), allow_nan=False), encoding="utf-8")
    temporary.replace(path)


def discover(source):
    choices = {}
    # Era-specific repositories take precedence over older all-era CAB repositories.
    paths = sorted(source.rglob("chrprfmech_*base-*.prefab")) + sorted(source.rglob("chrprfmech_*base-001"))
    paths.sort(key=lambda p: (any(part.endswith("-Mech") for part in p.parts), str(p).lower()))
    for path in paths:
        if path.is_file() and path.parent.name.lower() == "assetbundles":
            token = re.sub(r"^chrprfmech_|base-\d+(?:\.prefab)?$", "", path.name.lower())
            choices.setdefault(key(token), path)
    return choices


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--only", nargs="*", help="Optional bundle chassis names for a small preview import")
    args = parser.parse_args()
    source, output = args.source.resolve(), args.output.resolve()
    if not source.is_dir():
        parser.error(f"Asset directory does not exist: {source}")
    if output == source or source in output.parents:
        parser.error("The cache must be outside the source assets")
    output.mkdir(parents=True, exist_ok=True)
    choices = discover(source)
    if not choices:
        parser.error("No HBS chassis bundles found")
    selected = {key(n) for n in args.only} if args.only else set(choices)
    manifest_path = output / "catalog.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    entries = manifest.get("models", {}) if manifest.get("source") == str(source) else {}
    failures, reused = {}, 0
    converter = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    for number, (token, bundle) in enumerate(choices.items(), 1):
        if token not in selected:
            continue
        stat = bundle.stat()
        fingerprint = f"{VERSION}:{converter}:{bundle.relative_to(source)}:{stat.st_size}:{stat.st_mtime_ns}"
        identity = hashlib.sha256(fingerprint.encode()).hexdigest()[:20]
        target = output / "models" / (token + "-" + identity)
        descriptor = target / "model.json"
        try:
            if descriptor.is_file() and (target / "model.g3dj").is_file():
                info = json.loads(descriptor.read_text(encoding="utf-8"))
                complete = all((target / t).is_file() for t in info["textures"])
            else:
                complete = False
            if complete:
                reused += 1
            else:
                target.mkdir(parents=True, exist_ok=True)
                info = convert(bundle, target)
                print(f"[{number}/{len(choices)}] {token}: {info['triangles']} triangles", flush=True)
            entries[token] = {"descriptor": descriptor.relative_to(output).as_posix(),
                              "bundle": bundle.relative_to(source).as_posix()}
        except Exception as error:
            entries.pop(token, None)
            failures[token] = f"{type(error).__name__}: {error}"
            print(f"[{number}/{len(choices)}] {token}: using Gaea fallback ({error})", flush=True)
        gc.collect()
        # Publish only complete models; interruption leaves a usable catalog for already converted chassis.
        write_json(manifest_path, {"schema": VERSION, "source": str(source), "models": entries})
    entries = {k: v for k, v in entries.items() if k in choices}
    write_json(manifest_path, {"schema": VERSION, "source": str(source), "models": entries})
    write_json(output / "import-report.json", {"available": len(entries), "reused": reused, "failures": failures})
    print(f"HBS cache: {len(entries)} chassis available; {reused} unchanged; {len(failures)} using Gaea fallback.")
    return 0 if entries else 1


if __name__ == "__main__":
    sys.exit(main())
