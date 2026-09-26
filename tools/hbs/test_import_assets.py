"""Numerical and filtering boundaries; uses synthetic geometry, never CAB files."""
import unittest
from types import SimpleNamespace as Value
from unittest.mock import patch

import numpy as np

import import_assets as importer


class ImportTests(unittest.TestCase):
    def test_prefab_filter_excludes_alternative_damage_and_sensor_meshes(self):
        self.assertTrue(importer.intact('/chassis/mesh/mesh_LArm/LArm_whole/left_forearm'))
        for path in ('/mesh/Head_dmg/cockpit', '/mesh/leg_explode', '/BlipObject/mech', '/vfx/fire'):
            self.assertFalse(importer.intact(path), path)
        self.assertEqual('LA-forearm', importer.location('/mesh/mesh_LArm/LArm_whole/left_forearm'))
        self.assertEqual('LL-foot', importer.location('/mesh/mesh_LLeg/LLeg_whole/left_leg_foot'))
        self.assertEqual('pelvis', importer.location('/mesh/mesh_CTorso/CTorso_whole/centre_torso_pelvis'))

    def test_mesh_splitting_preserves_faces_and_all_vertex_attributes(self):
        vertices = np.arange(64).reshape(8, 8)
        faces = np.array([[0, 1, 2], [2, 3, 4], [4, 5, 6], [5, 6, 7]])
        sections = list(importer.split_mesh(vertices, faces, limit=5))
        reconstructed = []
        for points, indices in sections:
            self.assertLessEqual(len(points), 5)
            self.assertLess(max(indices), len(points))
            reconstructed.extend(points[np.array(indices).reshape(-1, 3)].tolist())
        self.assertEqual(vertices[faces].tolist(), reconstructed)

    def test_skinning_applies_bind_pose_then_bone_world_transform(self):
        bind = np.eye(4)
        bind[0, 3] = -2
        bind = Value(**{f'e{r}{c}': bind[r, c] for r in range(4) for c in range(4)})
        mesh = Value(m_BindPose=[bind], object_reader=Value(version=Value(as_tuple=lambda: (2017, 4, 1))))
        renderer = Value(m_Bones=[Value(m_PathID=7)])
        handler = Value(m_Vertices=[[2, 0, 0], [3, 0, 0], [2, 1, 0]],
                        m_Normals=[[0, 0, 1]] * 3, m_UV0=[[0, 0], [1, 0], [0, 1]],
                        m_BoneWeights=[[1, 0, 0, 0]] * 3, m_BoneIndices=[[0, 0, 0, 0]] * 3,
                        process=lambda: None, get_triangles=lambda: [[(0, 1, 2)]])
        world = np.eye(4)
        world[1, 3] = 10
        with patch.object(importer, 'get_mesh', return_value=mesh), patch.object(importer, 'MeshHandler', return_value=handler):
            positions, normals, uv, parts = importer.geometry(renderer, np.eye(4), {7: world})
        np.testing.assert_allclose(positions, [[0, 0, 40], [4, 0, 40], [0, 0, 44]])
        np.testing.assert_allclose(normals, [[0, 1, 0]] * 3)
        np.testing.assert_allclose(uv, [[0, 1], [1, 1], [0, 0]])
        a, b, c = parts[0][0]
        self.assertGreater(np.dot(np.cross(positions[b] - positions[a], positions[c] - positions[a]), normals[a]), 0)


if __name__ == '__main__':
    unittest.main()
