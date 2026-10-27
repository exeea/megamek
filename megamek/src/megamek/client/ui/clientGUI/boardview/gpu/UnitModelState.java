/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import jakarta.annotation.Nullable;
import megamek.client.ui.tileset.EquipmentModelPolicy;
import megamek.client.ui.tileset.UnitModelEquipment;
import megamek.client.ui.util.PlayerColour;
import megamek.common.alphaStrike.conversion.ASConverter;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.battlefieldSupport.BFSAssetType;
import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.battlefieldSupport.OverlayStyle;
import megamek.common.battlefieldSupport.StripeDirection;
import megamek.common.equipment.WeaponMounted;
import megamek.common.icons.Camouflage;
import megamek.common.options.OptionsConstants;
import megamek.common.units.AbstractBuildingEntity;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.EntityWeightClass;
import megamek.common.units.FighterSquadron;
import megamek.common.units.Infantry;
import megamek.common.units.Mek;
import megamek.common.units.ProneCause;
import megamek.common.units.ProtoMek;
import megamek.common.units.QuadMek;
import megamek.common.units.Tank;
import megamek.common.units.TripodMek;

/** Swing-owned capture after visibility filtering. Structure changes are independent of pose and damage changes. */
record UnitModelState(Structure structure, Appearance appearance, Pose pose) {
    record Structure(EntityMovementMode movement, List<UnitModelEquipment.Mount> equipment, List<Integer> members,
          int activeTroopers, boolean externalSearchlight, MekAnatomy anatomy, BodyForm bodyForm,
          UnitFamilyScale family, String unitIcon, int fieldGuns, @Nullable Building building) {
        Structure(EntityMovementMode movement, List<UnitModelEquipment.Mount> equipment, List<Integer> members,
              int activeTroopers, boolean externalSearchlight, MekAnatomy anatomy, BodyForm bodyForm,
              UnitFamilyScale family, String unitIcon, int fieldGuns) {
            this(movement, equipment, members, activeTroopers, externalSearchlight, anatomy, bodyForm, family, unitIcon,
                  fieldGuns, null);
        }
        Structure(EntityMovementMode movement, List<UnitModelEquipment.Mount> equipment, List<Integer> members,
              int activeTroopers, boolean externalSearchlight, MekAnatomy anatomy, BodyForm bodyForm,
              UnitFamilyScale family) {
            this(movement, equipment, members, activeTroopers, externalSearchlight, anatomy, bodyForm, family, null, 0);
        }
        Structure(EntityMovementMode movement, List<UnitModelEquipment.Mount> equipment, List<Integer> members,
              int activeTroopers, boolean externalSearchlight, MekAnatomy anatomy, BodyForm bodyForm) {
            this(movement, equipment, members, activeTroopers, externalSearchlight, anatomy, bodyForm,
                  anatomy == null ? UnitFamilyScale.DEFAULT : UnitFamilyScale.MEK);
        }
        Structure(EntityMovementMode movement, List<UnitModelEquipment.Mount> equipment, List<Integer> members,
              int activeTroopers, boolean externalSearchlight, MekAnatomy anatomy) {
            this(movement, equipment, members, activeTroopers, externalSearchlight, anatomy, null);
        }
        Structure(EntityMovementMode movement, List<UnitModelEquipment.Mount> equipment, List<Integer> members,
              int activeTroopers, boolean externalSearchlight) {
            this(movement, equipment, members, activeTroopers, externalSearchlight, null);
        }

        Structure {
            equipment = List.copyOf(equipment);
            members = List.copyOf(members);
        }

        String iconKey() {
            return unitIcon == null ? null : unitIcon.replace('\\', '/').toLowerCase(Locale.ROOT);
        }

        /** The same structure carrying only {@code mounts}: one roof turret or wall casemate of a building. */
        Structure withEquipment(List<UnitModelEquipment.Mount> mounts) {
            return new Structure(movement, mounts, members, activeTroopers, externalSearchlight, anatomy, bodyForm, family,
                  unitIcon, fieldGuns, building);
        }
    }

    /**
     * Where an advanced building's weapons sit in its drawn hex: the hex's height in levels, and each wall weapon's
     * casemate. A weapon not listed rides the roof turret.
     */
    record Building(int height, Map<Integer, Wall> walls) {
        Building {
            walls = Map.copyOf(walls);
        }
    }

    /**
     * A wall weapon's casemate: the face it fires from, 0-5 clockwise from the building's front, whose three hexsides
     * are its arc, and its floor, 0 for the ground floor.
     */
    record Wall(int face, int floor) { }

    /** Family-only structure; resting proportions never depend on transient gameplay height or posture. */
    record BodyForm(String configuration, int size, int turrets, List<FlightMember> fighters) {
        BodyForm(String configuration, int size, int turrets) {
            this(configuration, size, turrets, List.of());
        }

        BodyForm {
            fighters = List.copyOf(fighters);
        }
    }

    /** A squadron borrows actual visible member state; it never creates additional game entities. */
    record FlightMember(int id, Structure structure) { }

    /**
     * @param miniatureHeightScale the physical miniature's height as a multiple of the reference assault miniature,
     *                             or {@code null} when the unit is not on the {@link MiniatureHeights} list
     */
    record MekAnatomy(String configuration, List<String> hands, List<String> lowerArms, int size, int weightClass,
          @Nullable Float miniatureHeightScale) {
        MekAnatomy(String configuration, List<String> hands, List<String> lowerArms, int size, int weightClass) {
            this(configuration, hands, lowerArms, size, weightClass, null);
        }

        MekAnatomy(String configuration, List<String> hands, List<String> lowerArms) {
            this(configuration, hands, lowerArms, 3, EntityWeightClass.WEIGHT_HEAVY);
        }

        MekAnatomy {
            hands = List.copyOf(hands);
            lowerArms = List.copyOf(lowerArms);
        }

        boolean superHeavy() {
            return weightClass == EntityWeightClass.WEIGHT_SUPER_HEAVY;
        }
    }

    /** {@code bodyStage}: the whole-body damage overlay ({@link UnitModelSelection#bodyStage}), null for none. */
    record Appearance(Set<Integer> inoperableEquipment, boolean searchlightOn, Camo camo,
          Map<Integer, Appearance> fighters, UnitDamageDisplay.Stage bodyStage) {
        Appearance(Set<Integer> inoperableEquipment, boolean searchlightOn, Camo camo,
              Map<Integer, Appearance> fighters) {
            this(inoperableEquipment, searchlightOn, camo, fighters, null);
        }
        Appearance(Set<Integer> inoperableEquipment, boolean searchlightOn, Camo camo) {
            this(inoperableEquipment, searchlightOn, camo, Map.of());
        }

        Appearance {
            inoperableEquipment = Set.copyOf(inoperableEquipment);
            fighters = Map.copyOf(fighters);
        }
    }

    /** Resolved pixels cross the Swing boundary; neither the icon nor an AWT image reaches the renderer. */
    record Camo(String category, String filename, int rotation, int scale, int rgb, BoardScene.Pixels image,
          Marker marker) { }

    record Marker(int rgb, StripeDirection direction, OverlayStyle style, BoardScene.Pixels image) { }

    /**
     * @param canHullDown whether a combat vehicle could go hull down where it is, as only a fortified hex lets it
     *                    (TO:AR p.19), hull down or not
     */
    record Pose(ProneCause proneCause, int facing, int secondaryFacing, megamek.common.units.UnitLocation.Form form,
          boolean dead, boolean armsFlipped, boolean hullDown, boolean canHullDown) {
        Pose(ProneCause proneCause, int facing, int secondaryFacing, megamek.common.units.UnitLocation.Form form,
              boolean dead, boolean armsFlipped, boolean hullDown) {
            this(proneCause, facing, secondaryFacing, form, dead, armsFlipped, hullDown, false);
        }
        Pose(ProneCause proneCause, int facing, int secondaryFacing, megamek.common.units.UnitLocation.Form form,
              boolean dead, boolean armsFlipped) {
            this(proneCause, facing, secondaryFacing, form, dead, armsFlipped, false);
        }
        Pose(ProneCause proneCause, int facing, int secondaryFacing, megamek.common.units.UnitLocation.Form form,
              boolean dead) {
            this(proneCause, facing, secondaryFacing, form, dead, false);
        }
        Pose(ProneCause proneCause, int facing, int secondaryFacing, megamek.common.units.UnitLocation.Form form) {
            this(proneCause, facing, secondaryFacing, form, false);
        }
        Pose(ProneCause proneCause, int facing, int secondaryFacing) {
            this(proneCause, facing, secondaryFacing, null);
        }
    }

    static UnitModelState capture(Entity entity) {
        return capture(entity, null);
    }

    static UnitModelState capture(Entity entity, String unitIcon) {
        return capture(entity, unitIcon, -1);
    }

    /**
     * The Swing-owned tileset supplies the icon identity; the renderer only receives this immutable snapshot.
     *
     * @param part the drawn part of a large unit, or -1 for all of it: an armed building drawn hex by hex captures only
     *             the equipment in that hex
     */
    static UnitModelState capture(Entity entity, String unitIcon, int part) {
        List<UnitModelEquipment.Mount> equipment = new ArrayList<>();
        Set<Integer> inoperable = new TreeSet<>();
        var armed = entity instanceof AbstractBuildingEntity building && UnitModelEquipment.armedStructure(building)
              ? building : null;
        var hex = armed == null ? null : part < 0 ? armed.getPosition() : armed.getSecondaryPositions().get(part);
        Map<Integer, Wall> walls = new HashMap<>();
        for (var mount : entity.getEquipment()) {
            if (armed != null && part >= 0 && !java.util.Objects.equals(hex, armed.getLocationCoords(mount.getLocation()))) {
                continue;
            }
            // A building's turret-mounted weapons ride its roof; one facing a wall fires from a casemate in it.
            if (armed != null && mount instanceof WeaponMounted weapon && UnitModelEquipment.wallMounted(armed, weapon)) {
                walls.put(mount.getEquipmentNum(), new Wall(weapon.getFacing(), armed.getLocationLevel(mount.getLocation())));
            }
            var visual = UnitModelEquipment.describe(entity, mount);
            if (visual.policy() != EquipmentModelPolicy.NONE) {
                equipment.add(visual);
                if (mount.isInoperable()) {
                    inoperable.add(mount.getEquipmentNum());
                }
            }
        }
        List<Integer> members = new ArrayList<>();
        int troopers = 0;
        if (entity instanceof BattleArmor armor) {
            for (int location = 1; location < armor.locations(); location++) {
                if (armor.getInternal(location) > 0) {
                    members.add(location);
                }
            }
            troopers = members.size();
        } else if (entity instanceof Infantry infantry) {
            troopers = Math.max(0, infantry.getActiveTroopers());
        } else if (entity instanceof BattlefieldSupportAsset asset
              && (asset.getAssetType() == BFSAssetType.CONV_INFANTRY || asset.getAssetType() == BFSAssetType.BATTLE_ARMOR)) {
            // A support card has no personnel locations. Its family marker is one representative figure.
            troopers = 1;
            members.add(1);
        }
        Camouflage camo = entity.getCamouflageOrElseOwners();
        Marker marker = entity instanceof BattlefieldSupportAsset && camo != null && camo.getOverlayStyle().isVisible()
              ? new Marker(camo.getOverlayColor().getRGB(), camo.getOverlayDirection(), camo.getOverlayStyle(), null) : null;
        Camo appearance = camo == null ? null : new Camo(camo.getCategory(), camo.getFilename(),
              camo.getRotationAngle(), camo.getScale(),
              camo.isColourCamouflage() ? PlayerColour.parseFromString(camo.getFilename()).getHex() : 0xFFFFFF, null, marker);
        MekAnatomy anatomy = entity instanceof Mek mek ? new MekAnatomy(mek instanceof QuadMek ? "quad"
              : mek instanceof TripodMek ? "tripod" : "biped",
              UnitModelEquipment.armsWith(mek, Mek.ACTUATOR_HAND), UnitModelEquipment.armsWith(mek, Mek.ACTUATOR_LOWER_ARM),
              ASConverter.sizeFor(mek), mek.getWeightClass(),
              MiniatureHeights.instance().heightScale(mek.getUnitFileUUID())) : null;
        List<FlightMember> fighters = new ArrayList<>();
        Map<Integer, Appearance> fighterAppearances = new HashMap<>();
        if (entity instanceof FighterSquadron squadron && entity.getGame() != null) {
            for (var fighter : squadron.getActiveSubEntities()) {
                var member = capture(fighter);
                fighters.add(new FlightMember(fighter.getId(), member.structure()));
                fighterAppearances.put(fighter.getId(), member.appearance());
            }
        }
        var movement = entity instanceof megamek.common.units.QuadVee ? EntityMovementMode.QUAD : entity.getMovementMode();
        var form = new BodyForm(entity instanceof ProtoMek proto ? (proto.isQuad() ? "quad-proto"
              : proto.isGlider() ? "glider-proto" : "proto") : movement.name(),
              anatomy != null ? anatomy.size() : ASConverter.canConvert(entity) ? ASConverter.sizeFor(entity) : 2,
              entity instanceof Tank tank ? tank.getTurretCount() : 0, fighters);
        // Meks/vehicles receive a generic rules searchlight at game start; that is not an authored lamp housing.
        // The design quirk still describes hardware when optional quirk effects are disabled. Mounted lamps
        // already have their own equipment entries, and explicitly added lights on other families remain visible.
        boolean externalLamp = entity.hasExternalSearchlight() && (!entity.getsAutoExternalSearchlight()
              || entity.getQuirks().booleanOption(OptionsConstants.QUIRK_POS_SEARCHLIGHT));
        return new UnitModelState(new Structure(movement, equipment, members, troopers,
              externalLamp, anatomy, form, UnitFamilyScale.forEntity(entity), unitIcon,
              entity instanceof ConvInfantry infantry ? infantry.activeFieldWeapons().size() : 0,
              armed == null ? null : new Building(hex == null ? 1 : armed.getHeight(hex), walls)),
              new Appearance(inoperable, entity.isUsingSearchlight(), appearance, fighterAppearances,
                    UnitModelSelection.bodyStage(entity)),
              new Pose(entity instanceof Mek ? entity.getProneCause() : ProneCause.NONE,
                    entity.getFacing(), entity.getSecondaryFacing(), megamek.common.units.UnitLocation.Form.capture(entity),
                    entity.isDestroyed() || entity.isDoomed(),
                    // Ask both: a unit that cannot flip should never be shown flipped, whatever the flag says.
                    entity.canFlipArms() && entity.getArmsFlipped(), entity.isHullDown(), canHullDown(entity)));
    }

    /** Whether a combat vehicle could go hull down where it is: a fortified hex, with the rule in play (TO:AR p.19). */
    private static boolean canHullDown(Entity entity) {
        var game = entity.getGame();
        var hex = game == null ? null : game.getHexOf(entity);
        return entity instanceof Tank tank && tank.isHullDownCapable() && hex != null
              && hex.containsTerrain(megamek.common.units.Terrains.FORTIFIED)
              && game.getOptions().booleanOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_HULL_DOWN);
    }
}
