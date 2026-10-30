/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.dialogs.advancedsearch.ASSearchFilter;
import megamek.client.ui.dialogs.advancedsearch.AdvancedSearchEquipmentClass;
import megamek.client.ui.dialogs.advancedsearch.BFSSearchFilter;
import megamek.client.ui.dialogs.advancedsearch.FilterParsingException;
import megamek.client.ui.dialogs.advancedsearch.FilterToken;
import megamek.client.ui.dialogs.advancedsearch.MekSearchFilter;
import megamek.common.SimpleTechLevel;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.battlefieldSupport.BFSArtilleryType;
import megamek.common.battlefieldSupport.BFSAssetType;
import megamek.common.battlefieldSupport.BFSSpecialType;
import megamek.common.equipment.ArmorType;
import megamek.common.equipment.Engine;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.EquipmentTypeLookup;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.WeaponType;
import megamek.common.loaders.MekSummary;
import megamek.common.options.IGameOptions;
import megamek.common.options.IOption;
import megamek.common.options.IOptionGroup;
import megamek.common.options.Quirks;
import megamek.common.options.WeaponQuirks;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.Mek;
import megamek.common.units.UnitRole;
import megamek.logging.MMLogger;

/**
 * The Requisition view's advanced search: the unit selector's Total Warfare, Alpha Strike and Battlefield Support
 * searches as one immutable set of criteria, each a {@link Field} with a {@link Value}. The criteria are matched with
 * MegaMek's own filters ({@link MekSearchFilter}, {@link ASSearchFilter}, {@link BFSSearchFilter}), the ones the unit
 * selector dialog's advanced search uses, plus a run range the dialog lacks. The Total Warfare and Alpha Strike
 * criteria read the listed unit; the Battlefield Support ones its asset form, as the dialog does.
 */
public final class RequisitionAdvancedSearch {
    private static final MMLogger LOGGER = MMLogger.create(RequisitionAdvancedSearch.class);

    /** The tab a criterion sits on. */
    public enum Section {
        TOTAL_WARFARE("Lobby.requisition.advanced.tw"),
        ALPHA_STRIKE("Lobby.requisition.advanced.as"),
        BATTLEFIELD_SUPPORT("Lobby.requisition.advanced.bf");

        private final String messageKey;

        Section(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    /** What a criterion's value is like, and so how a view asks for it. */
    public enum Kind {
        /** A whole-number span, {@link Range}. */
        RANGE,
        /** One of a few levels, {@link Level}. */
        LEVEL,
        /** Must be, or must not be, {@link Flag}. */
        FLAG,
        /** Some of the field's choices, {@link Picks}. */
        PICKS,
        /** A damage span over {@link ASSearchFilter#DAMAGE_VALUES}, a {@link Range} of indices. */
        DAMAGE,
        /** An Alpha Strike movement mode and its span, {@link Movement}. */
        MOVEMENT,
        /** "At least" values for some of the field's choices, {@link Minimums}. */
        MINIMUMS,
        /** The weapon and equipment expression, {@link Equipment}. */
        EQUIPMENT
    }

    /** One criterion of the advanced search. */
    public enum Field {
        WALK(Section.TOTAL_WARFARE, Kind.RANGE),
        RUN(Section.TOTAL_WARFARE, Kind.RANGE),
        JUMP(Section.TOTAL_WARFARE, Kind.RANGE),
        TONS(Section.TOTAL_WARFARE, Kind.RANGE),
        YEAR(Section.TOTAL_WARFARE, Kind.RANGE),
        BV(Section.TOTAL_WARFARE, Kind.RANGE),
        ARMOR(Section.TOTAL_WARFARE, Kind.LEVEL),
        UNIT_TYPE(Section.TOTAL_WARFARE, Kind.PICKS),
        TECH_BASE(Section.TOTAL_WARFARE, Kind.PICKS),
        TECH_LEVEL(Section.TOTAL_WARFARE, Kind.PICKS),
        MOVE_MODE(Section.TOTAL_WARFARE, Kind.PICKS),
        ARMOR_TYPE(Section.TOTAL_WARFARE, Kind.PICKS),
        INTERNALS(Section.TOTAL_WARFARE, Kind.PICKS),
        ENGINE(Section.TOTAL_WARFARE, Kind.PICKS),
        COCKPIT(Section.TOTAL_WARFARE, Kind.PICKS),
        GYRO(Section.TOTAL_WARFARE, Kind.PICKS),
        QUIRKS(Section.TOTAL_WARFARE, Kind.PICKS),
        WEAPON_QUIRKS(Section.TOTAL_WARFARE, Kind.PICKS),
        OMNI(Section.TOTAL_WARFARE, Kind.FLAG),
        MILITARY(Section.TOTAL_WARFARE, Kind.FLAG),
        INDUSTRIAL(Section.TOTAL_WARFARE, Kind.FLAG),
        CLAN_ENGINE(Section.TOTAL_WARFARE, Kind.FLAG),
        PATCHWORK(Section.TOTAL_WARFARE, Kind.FLAG),
        SUPPORT_VEHICLE(Section.TOTAL_WARFARE, Kind.FLAG),
        MOUNTED_INFANTRY(Section.TOTAL_WARFARE, Kind.FLAG),
        WATER_ONLY(Section.TOTAL_WARFARE, Kind.FLAG),
        FRANKEN_MEK(Section.TOTAL_WARFARE, Kind.FLAG),
        OFFICIAL(Section.TOTAL_WARFARE, Kind.FLAG),
        INVALID(Section.TOTAL_WARFARE, Kind.FLAG),
        FAILED_EQUIPMENT(Section.TOTAL_WARFARE, Kind.FLAG),
        DOOMED_ON_GROUND(Section.TOTAL_WARFARE, Kind.FLAG),
        DOOMED_IN_ATMOSPHERE(Section.TOTAL_WARFARE, Kind.FLAG),
        DOOMED_IN_SPACE(Section.TOTAL_WARFARE, Kind.FLAG),
        DOOMED_IN_EXTREME_TEMP(Section.TOTAL_WARFARE, Kind.FLAG),
        DOOMED_IN_VACUUM(Section.TOTAL_WARFARE, Kind.FLAG),
        EQUIPMENT(Section.TOTAL_WARFARE, Kind.EQUIPMENT),

        AS_TYPE(Section.ALPHA_STRIKE, Kind.PICKS),
        AS_ROLE(Section.ALPHA_STRIKE, Kind.PICKS),
        AS_SIZE(Section.ALPHA_STRIKE, Kind.PICKS),
        AS_TMM(Section.ALPHA_STRIKE, Kind.PICKS),
        AS_MOVE(Section.ALPHA_STRIKE, Kind.MOVEMENT),
        AS_ARMOR(Section.ALPHA_STRIKE, Kind.RANGE),
        AS_STRUCTURE(Section.ALPHA_STRIKE, Kind.RANGE),
        AS_THRESHOLD(Section.ALPHA_STRIKE, Kind.RANGE),
        AS_DAMAGE_S(Section.ALPHA_STRIKE, Kind.DAMAGE),
        AS_DAMAGE_M(Section.ALPHA_STRIKE, Kind.DAMAGE),
        AS_DAMAGE_L(Section.ALPHA_STRIKE, Kind.DAMAGE),
        AS_DAMAGE_E(Section.ALPHA_STRIKE, Kind.DAMAGE),
        AS_OV(Section.ALPHA_STRIKE, Kind.PICKS),
        AS_PV(Section.ALPHA_STRIKE, Kind.RANGE),
        AS_SPECIALS(Section.ALPHA_STRIKE, Kind.PICKS),

        BF_TYPE(Section.BATTLEFIELD_SUPPORT, Kind.PICKS),
        BF_MOVE_MODE(Section.BATTLEFIELD_SUPPORT, Kind.PICKS),
        BF_MP(Section.BATTLEFIELD_SUPPORT, Kind.RANGE),
        BF_TMM(Section.BATTLEFIELD_SUPPORT, Kind.RANGE),
        BF_SKILL(Section.BATTLEFIELD_SUPPORT, Kind.RANGE),
        BF_DAMAGE(Section.BATTLEFIELD_SUPPORT, Kind.RANGE),
        BF_DAMAGE_PER_HIT(Section.BATTLEFIELD_SUPPORT, Kind.RANGE),
        BF_DAMAGE_HITS(Section.BATTLEFIELD_SUPPORT, Kind.RANGE),
        BF_LONG_RANGE(Section.BATTLEFIELD_SUPPORT, Kind.RANGE),
        BF_DESTROY_CHECK(Section.BATTLEFIELD_SUPPORT, Kind.RANGE),
        BF_THRESHOLD(Section.BATTLEFIELD_SUPPORT, Kind.RANGE),
        BF_BSP(Section.BATTLEFIELD_SUPPORT, Kind.RANGE),
        BF_BV(Section.BATTLEFIELD_SUPPORT, Kind.RANGE),
        BF_SPECIALS(Section.BATTLEFIELD_SUPPORT, Kind.PICKS),
        BF_NUMERIC_SPECIALS(Section.BATTLEFIELD_SUPPORT, Kind.MINIMUMS),
        BF_ARTILLERY(Section.BATTLEFIELD_SUPPORT, Kind.PICKS);

        private final Section section;
        private final Kind kind;

        Field(Section section, Kind kind) {
            this.section = section;
            this.kind = kind;
        }

        public Section section() {
            return section;
        }

        public Kind kind() {
            return kind;
        }

        public String messageKey() {
            return "Lobby.requisition.advanced.field." + name();
        }

        /** Whether a pick can also be "must not" (the Total Warfare lists, as the dialog's tri-state lists). */
        public boolean excludes() {
            return (section == Section.TOTAL_WARFARE) && (kind == Kind.PICKS);
        }

        /** Whether matching asks for every pick rather than any (the specials). */
        public boolean needsAll() {
            return (this == AS_SPECIALS) || (this == BF_SPECIALS);
        }

        /** Whether the picks may be none and still be at work: Artillery, of any type. */
        public boolean emptyAllowed() {
            return this == BF_ARTILLERY;
        }

        /** Whether the "any or all" switch applies: the quirk lists, where the dialog offers "and" and "or". */
        public boolean anyOrAll() {
            return (this == QUIRKS) || (this == WEAPON_QUIRKS);
        }

        /** The fields of {@code section}, in order. */
        public static List<Field> of(Section section) {
            return Arrays.stream(values()).filter(field -> field.section == section).toList();
        }
    }

    /** One of a field's choices: the value the filter reads and the text a view shows. */
    public record Choice(Object value, String label) { }

    /** A criterion's value. */
    public sealed interface Value permits Range, Level, Flag, Picks, Movement, Minimums, Equipment { }

    /** A span, either end open when null. For {@link Kind#DAMAGE}, indices into the damage values. */
    public record Range(@Nullable Integer min, @Nullable Integer max) implements Value {
        public boolean matches(int value) {
            return ((min == null) || (value >= min)) && ((max == null) || (value <= max));
        }

        boolean open() {
            return (min == null) && (max == null);
        }
    }

    /** One of the field's levels, an index into its choices. */
    public record Level(int index) implements Value { }

    /** Must be ({@code yes}), or must not be. */
    public record Flag(boolean yes) implements Value { }

    /**
     * Picked choice values: those a unit must have ({@code in}) and those it must not ({@code out}); {@code any}
     * matches a unit with any of {@code in} rather than all, for the fields where that is a choice.
     */
    public record Picks(List<Object> in, List<Object> out, boolean any) implements Value {
        public Picks {
            in = List.copyOf(in);
            out = List.copyOf(out);
        }

        /** These picks with {@code value} must-have ({@code TRUE}), must-not ({@code FALSE}) or not picked (null). */
        public Picks with(Object value, @Nullable Boolean state) {
            List<Object> nextIn = new ArrayList<>(in);
            List<Object> nextOut = new ArrayList<>(out);
            nextIn.remove(value);
            nextOut.remove(value);
            if (Boolean.TRUE.equals(state)) {
                nextIn.add(value);
            } else if (Boolean.FALSE.equals(state)) {
                nextOut.add(value);
            }
            return new Picks(nextIn, nextOut, any);
        }

        /** Must-have ({@code TRUE}), must-not ({@code FALSE}) or not picked (null). */
        public @Nullable Boolean state(Object value) {
            return in.contains(value) ? Boolean.TRUE : (out.contains(value) ? Boolean.FALSE : null);
        }

        public boolean isEmpty() {
            return in.isEmpty() && out.isEmpty();
        }
    }

    /** An Alpha Strike movement mode code (see {@link ASSearchFilter#MOVE_MODES}) and its span in inches. */
    public record Movement(String mode, Range range) implements Value { }

    /** "At least" values by choice value. */
    public record Minimums(Map<Object, Integer> values) implements Value {
        public Minimums {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    /** The weapon and equipment expression's terms. */
    public record Equipment(List<Term> terms) implements Value {
        public static final Equipment EMPTY = new Equipment(List.of());

        public Equipment {
            terms = List.copyOf(terms);
        }

        private @Nullable Term last() {
            return terms.isEmpty() ? null : terms.get(terms.size() - 1);
        }

        private int openParens() {
            int open = 0;
            for (Term term : terms) {
                open += (term.kind() == TermKind.OPEN) ? 1 : ((term.kind() == TermKind.CLOSE) ? -1 : 0);
            }
            return open;
        }

        /** Whether an item or an opening bracket may come next: at the start, or after an operator or "(". */
        public boolean canAddItem() {
            Term last = last();
            return (last == null) || last.kind().operator() || (last.kind() == TermKind.OPEN);
        }

        /** Whether "and" or "or" may come next: after an item or ")". */
        public boolean canAddOperator() {
            Term last = last();
            return (last != null) && ((last.kind() == TermKind.ITEM) || (last.kind() == TermKind.CLOSE));
        }

        /** Whether ")" may come next: after an item or ")" with a bracket open. */
        public boolean canClose() {
            return canAddOperator() && (openParens() > 0);
        }

        /** Whether the expression can be read: not empty, brackets closed and not ending on an operator. */
        public boolean complete() {
            return canAddOperator() && (openParens() == 0);
        }

        /** This expression and {@code term}, or this one when {@code term} may not come next. */
        public Equipment plus(Term term) {
            boolean allowed = switch (term.kind()) {
                case ITEM, OPEN -> canAddItem();
                case AND, OR -> canAddOperator();
                case CLOSE -> canClose();
            };
            if (!allowed) {
                return this;
            }
            List<Term> next = new ArrayList<>(terms);
            next.add(term);
            return new Equipment(next);
        }

        /** This expression without its last term. */
        public Equipment back() {
            return terms.isEmpty() ? this : new Equipment(terms.subList(0, terms.size() - 1));
        }

        public String text() {
            return terms.stream().map(Term::text).collect(Collectors.joining(" "));
        }
    }

    /** What a term of the equipment expression is. */
    public enum TermKind {
        ITEM, AND, OR, OPEN, CLOSE;

        boolean operator() {
            return (this == AND) || (this == OR);
        }
    }

    /**
     * A term of the equipment expression. An item names an equipment type's internal name or an
     * {@link AdvancedSearchEquipmentClass}, with a count: at least {@code count} when {@code atLeast}, else fewer.
     */
    public record Term(TermKind kind, String key, String label, int count, boolean atLeast) {
        public static final Term AND = new Term(TermKind.AND, "and", "", 0, true);
        public static final Term OR = new Term(TermKind.OR, "or", "", 0, true);
        public static final Term OPEN = new Term(TermKind.OPEN, "(", "", 0, true);
        public static final Term CLOSE = new Term(TermKind.CLOSE, ")", "", 0, true);

        public static Term item(String key, String label, int count, boolean atLeast) {
            return new Term(TermKind.ITEM, key, label, Math.max(0, count), atLeast);
        }

        /** The term as the dialog saves it, which {@link FilterToken#parse} reads. */
        public String token() {
            return (kind == TermKind.ITEM) ? key + FilterToken.TOKEN_SEPARATOR + count + FilterToken.TOKEN_SEPARATOR
                  + (atLeast ? FilterToken.TOKEN_ATLEAST : "<") : key;
        }

        public String text() {
            return switch (kind) {
                case ITEM -> Messages.getString(atLeast ? "Lobby.requisition.advanced.atLeastItem"
                      : "Lobby.requisition.advanced.fewerItem", String.valueOf(count), label);
                case AND -> Messages.getString("Lobby.requisition.advanced.and");
                case OR -> Messages.getString("Lobby.requisition.advanced.or");
                default -> key;
            };
        }
    }

    /** What an equipment choice is: a group of weapons or equipment, a weapon, or other equipment. */
    public enum EquipmentGroup { CLASS, WEAPON, EQUIPMENT }

    /** An equipment choice for the expression: its key ({@link Term#key()}), name and group. */
    public record EquipmentChoice(String key, String label, EquipmentGroup group) { }

    /** No criteria: every unit passes. */
    public static final RequisitionAdvancedSearch NONE = new RequisitionAdvancedSearch(new EnumMap<>(Field.class));

    private static final Map<Field, List<Choice>> CHOICES = new EnumMap<>(Field.class);
    private static List<EquipmentChoice> equipmentChoices;

    private final Map<Field, Value> values;
    private final @Nullable MekSearchFilter totalWarfare;
    private final ASSearchFilter alphaStrike;
    private final BFSSearchFilter battlefieldSupport;

    private RequisitionAdvancedSearch(Map<Field, Value> values) {
        this.values = Collections.unmodifiableMap(values);
        totalWarfare = totalWarfareFilter(values);
        alphaStrike = alphaStrikeFilter(values);
        battlefieldSupport = battlefieldSupportFilter(values);
    }

    /** These criteria with {@code field} set to {@code value}; a null or empty value takes the field out. */
    public RequisitionAdvancedSearch with(Field field, @Nullable Value value) {
        Map<Field, Value> next = new EnumMap<>(Field.class);
        next.putAll(values);
        if (inUse(field, value)) {
            next.put(field, value);
        } else {
            next.remove(field);
        }
        return next.equals(values) ? this : new RequisitionAdvancedSearch(next);
    }

    /** These criteria without {@code field}. */
    public RequisitionAdvancedSearch without(Field field) {
        return with(field, null);
    }

    /** These criteria without any of {@code section}'s. */
    public RequisitionAdvancedSearch without(Section section) {
        RequisitionAdvancedSearch next = this;
        for (Field field : Field.of(section)) {
            next = next.without(field);
        }
        return next;
    }

    /** The value of {@code field}, or null when it is not in use. */
    public @Nullable Value value(Field field) {
        return values.get(field);
    }

    /** The criteria in use, in field order. */
    public Map<Field, Value> values() {
        return values;
    }

    /** How many criteria are in use. */
    public int count() {
        return values.size();
    }

    /** How many of {@code section}'s criteria are in use. */
    public int count(Section section) {
        return (int) values.keySet().stream().filter(field -> field.section() == section).count();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Whether {@code unit} passes every criterion in use; {@code asset} is its Battlefield Support asset form (itself
     * for an asset), or null, which no Battlefield Support criterion lets through.
     */
    public boolean matches(MekSummary unit, @Nullable MekSummary asset) {
        if (values.isEmpty()) {
            return true;
        }
        if (values.get(Field.RUN) instanceof Range run && !run.matches(unit.getRunMp())) {
            return false;
        }
        return ((totalWarfare == null) || MekSearchFilter.isMatch(unit, totalWarfare)) && alphaStrike.matches(unit)
              && battlefieldSupport.matches(asset);
    }

    @Override
    public boolean equals(Object other) {
        return (other instanceof RequisitionAdvancedSearch search) && search.values.equals(values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "RequisitionAdvancedSearch" + values;
    }

    private static boolean inUse(Field field, @Nullable Value value) {
        if (value == null) {
            return false;
        }
        return switch (value) {
            case Range range -> !range.open();
            case Picks picks -> !picks.isEmpty() || field.emptyAllowed();
            case Minimums minimums -> !minimums.values().isEmpty();
            case Equipment equipment -> !equipment.terms().isEmpty();
            case Movement movement -> true;
            case Level level -> level.index() > 0;
            case Flag flag -> true;
        };
    }

    // ---- Describing ----

    /** The field's name, for a label or a tag. */
    public static String label(Field field) {
        return Messages.getString(field.messageKey());
    }

    /** {@code field}'s criterion as a tag shows it: its name and value. */
    public String describe(Field field) {
        Value value = values.get(field);
        return (value == null) ? label(field) : Messages.getString("Lobby.requisition.advanced.tag", label(field),
              describe(field, value));
    }

    /** {@code value} of {@code field} in words. */
    public static String describe(Field field, Value value) {
        return switch (value) {
            case Range range -> (field.kind() == Kind.DAMAGE) ? damageSpan(range) : span(range);
            case Level level -> choiceLabel(field, level.index());
            case Flag flag -> Messages.getString(flag.yes() ? "Lobby.requisition.advanced.yes"
                  : "Lobby.requisition.advanced.no");
            case Picks picks -> picks(field, picks);
            case Movement movement -> Messages.getString("Lobby.requisition.advanced.moveSpan",
                  moveModeLabel(movement.mode()), span(movement.range()));
            case Minimums minimums -> minimums.values().entrySet().stream()
                  .map(entry -> Messages.getString("Lobby.requisition.advanced.atLeast",
                        labelOf(field, entry.getKey()), String.valueOf(entry.getValue())))
                  .collect(Collectors.joining(", "));
            case Equipment equipment -> equipment.text();
        };
    }

    /** A span in words: "4–6", "≥ 4" or "≤ 6". */
    public static String span(Range range) {
        if ((range.min() != null) && (range.max() != null)) {
            return range.min().equals(range.max()) ? String.valueOf(range.min())
                  : Messages.getString("Lobby.requisition.advanced.between", String.valueOf(range.min()),
                  String.valueOf(range.max()));
        }
        return (range.min() != null) ? Messages.getString("Lobby.requisition.advanced.from", String.valueOf(range.min()))
              : Messages.getString("Lobby.requisition.advanced.to", String.valueOf(range.max()));
    }

    private static String damageSpan(Range range) {
        List<String> names = damageLabels();
        String from = names.get(clampIndex(range.min(), 0));
        String to = names.get(clampIndex(range.max(), names.size() - 1));
        return from.equals(to) ? from : Messages.getString("Lobby.requisition.advanced.between", from, to);
    }

    private static int clampIndex(@Nullable Integer index, int fallback) {
        int last = ASSearchFilter.DAMAGE_VALUES.size() - 1;
        return (index == null) ? fallback : Math.max(0, Math.min(last, index));
    }

    /** The damage values' names, in the order of {@link ASSearchFilter#DAMAGE_VALUES}. */
    public static List<String> damageLabels() {
        return ASSearchFilter.DAMAGE_VALUES.stream().map(Object::toString).toList();
    }

    /** An Alpha Strike movement mode code's name: "Standard" for the plain one, else the code. */
    public static String moveModeLabel(String mode) {
        return mode.isEmpty() ? Messages.getString("Lobby.requisition.advanced.standardMove") : mode;
    }

    private static String picks(Field field, Picks picks) {
        List<String> parts = new ArrayList<>();
        for (Object value : picks.in()) {
            parts.add(labelOf(field, value));
        }
        for (Object value : picks.out()) {
            parts.add(Messages.getString("Lobby.requisition.advanced.not", labelOf(field, value)));
        }
        if (parts.isEmpty()) {
            return Messages.getString("Lobby.requisition.advanced.anyType");
        }
        String joiner = (picks.any() || !field.anyOrAll()) && !field.needsAll()
              ? Messages.getString("Lobby.requisition.advanced.orJoin")
              : Messages.getString("Lobby.requisition.advanced.andJoin");
        return String.join(picks.in().size() > 1 ? joiner : ", ", parts);
    }

    /** The name of {@code value} among {@code field}'s choices. */
    public static String labelOf(Field field, Object value) {
        for (Choice choice : choices(field)) {
            if (choice.value().equals(value)) {
                return choice.label();
            }
        }
        return String.valueOf(value);
    }

    private static String choiceLabel(Field field, int index) {
        List<Choice> choices = choices(field);
        return ((index >= 0) && (index < choices.size())) ? choices.get(index).label() : String.valueOf(index);
    }

    // ---- Choices ----

    /** {@code field}'s choices, in the order a view offers them; none for fields without a fixed set. */
    public static synchronized List<Choice> choices(Field field) {
        return CHOICES.computeIfAbsent(field, RequisitionAdvancedSearch::makeChoices);
    }

    private static List<Choice> makeChoices(Field field) {
        return switch (field) {
            case ARMOR -> List.of(new Choice(0, Messages.getString("MekSelectorDialog.Search.Any")),
                  new Choice(1, Messages.getString("MekSelectorDialog.Search.Armor25")),
                  new Choice(2, Messages.getString("MekSelectorDialog.Search.Armor50")),
                  new Choice(3, Messages.getString("MekSelectorDialog.Search.Armor75")),
                  new Choice(4, Messages.getString("MekSelectorDialog.Search.Armor90")));
            case UNIT_TYPE -> UNIT_TYPES.stream()
                  .map(type -> new Choice(type, Messages.getString("MekSelectorDialog.Search." + type))).toList();
            case TECH_BASE -> Entity.getTechBaseDescriptions().stream().map(base -> new Choice(base, base)).toList();
            case TECH_LEVEL -> coded(SimpleTechLevel.getAllSimpleTechLevelCodeName(), false);
            case MOVE_MODE -> Arrays.stream(EntityMovementMode.values()).map(EntityMovementMode::toString).distinct()
                  .map(mode -> new Choice(mode, mode)).toList();
            case ARMOR_TYPE -> coded(ArmorType.getAllArmorCodeName(), true);
            case INTERNALS -> coded(EquipmentType.getAllStructureCodeName(), true);
            case ENGINE -> coded(Engine.getAllEngineCodeName(), true);
            case COCKPIT -> coded(Mek.getAllCockpitCodeName(), true);
            case GYRO -> coded(Entity.getAllGyroCodeName(), true);
            case QUIRKS -> quirks(Quirks::new);
            case WEAPON_QUIRKS -> quirks(WeaponQuirks::new);
            case AS_TYPE -> List.of(ASUnitType.BM, ASUnitType.IM, ASUnitType.PM, ASUnitType.CV, ASUnitType.SV,
                  ASUnitType.CI, ASUnitType.BA, ASUnitType.AF, ASUnitType.CF).stream()
                  .map(type -> new Choice(type, type.name())).toList();
            case AS_ROLE -> Arrays.stream(UnitRole.values()).map(role -> new Choice(role, role.toString())).toList();
            case AS_SIZE -> numbers(1, 5);
            case AS_TMM -> numbers(0, 5);
            case AS_OV -> numbers(0, 4);
            case AS_MOVE -> ASSearchFilter.MOVE_MODES.stream().map(mode -> new Choice(mode, moveModeLabel(mode)))
                  .toList();
            case AS_SPECIALS -> Arrays.stream(BattleForceSUA.values())
                  .sorted(Comparator.comparing(BattleForceSUA::name, String.CASE_INSENSITIVE_ORDER))
                  .map(sua -> new Choice(sua, sua.name())).toList();
            case BF_TYPE -> Arrays.stream(BFSAssetType.values()).map(type -> new Choice(type,
                  Messages.getString("BFSAdvancedSearchPanel.assetType." + type.name()))).toList();
            case BF_MOVE_MODE -> BFSSearchFilter.MOVE_MODES.stream().map(mode -> new Choice(mode,
                  Messages.getString("BFSAdvancedSearchPanel.movement." + mode.name()))).toList();
            case BF_SPECIALS -> BFSSearchFilter.PLAIN_SPECIALS.stream()
                  .map(type -> new Choice(type, type.canonicalCode())).toList();
            case BF_NUMERIC_SPECIALS -> BFSSearchFilter.NUMERIC_SPECIALS.stream()
                  .map(type -> new Choice(type, Messages.getString("BFSAdvancedSearchPanel.numericSpecial",
                        type.canonicalCode(), Messages.getString("BFSAdvancedSearchPanel.special." + type.name()))))
                  .toList();
            case BF_ARTILLERY -> Arrays.stream(BFSArtilleryType.values()).map(type -> new Choice(type,
                  Messages.getString("BFSAdvancedSearchPanel.artillery." + type.name()))).toList();
            default -> List.of();
        };
    }

    /** The dialog's unit type switches, by their message key suffix. */
    static final List<String> UNIT_TYPES = List.of("Mek", "BipedMek", "Quad", "Tripod", "QuadVee", "LAM",
          "ProtoMek", "Tank", "SupportTank", "LargeSupportTank", "SuperHeavyTank", "VTOL", "SupportVTOL",
          "GunEmplacement", "Infantry", "BattleArmor", "Aero", "AerospaceFighter", "ConvFighter", "FixedWingSupport",
          "SmallCraft", "Dropship", "Jumpship", "Warship", "SpaceStation");

    private static List<Choice> coded(Map<Integer, String> namesByCode, boolean byName) {
        List<Choice> choices = new ArrayList<>();
        new TreeMap<>(namesByCode).forEach((code, name) -> choices.add(new Choice(code, name)));
        if (byName) {
            choices.sort(Comparator.comparing(Choice::label, String.CASE_INSENSITIVE_ORDER));
        }
        return List.copyOf(choices);
    }

    private static List<Choice> numbers(int from, int to) {
        List<Choice> choices = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            choices.add(new Choice(i, String.valueOf(i)));
        }
        return List.copyOf(choices);
    }

    private static List<Choice> quirks(Supplier<IGameOptions> options) {
        List<String> names = new ArrayList<>();
        try {
            IGameOptions all = options.get();
            for (Enumeration<IOptionGroup> groups = all.getGroups(); groups.hasMoreElements(); ) {
                for (Enumeration<IOption> each = groups.nextElement().getOptions(); each.hasMoreElements(); ) {
                    IOption option = each.nextElement();
                    if (option != null) {
                        names.add(option.getDisplayableNameWithValue());
                    }
                }
            }
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "Could not list the quirks for the advanced search");
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names.stream().distinct().map(name -> new Choice(name, name)).toList();
    }

    /**
     * The equipment the expression can name, as the dialog's tables list it: the weapon and equipment groups, then
     * every weapon and every other equipment type by name.
     */
    public static synchronized List<EquipmentChoice> equipmentChoices() {
        if (equipmentChoices == null) {
            List<EquipmentChoice> choices = new ArrayList<>();
            for (AdvancedSearchEquipmentClass group : AdvancedSearchEquipmentClass.values()) {
                if (group != AdvancedSearchEquipmentClass.EMPTY) {
                    choices.add(new EquipmentChoice(group.name(), group.toString(), EquipmentGroup.CLASS));
                }
            }
            List<EquipmentChoice> types = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            List<EquipmentType> all = new ArrayList<>(Collections.list(EquipmentType.getAllTypes()));
            EquipmentType coolantPod = EquipmentType.get(EquipmentTypeLookup.COOLANT_POD);
            if (coolantPod != null) {
                all.add(coolantPod);
            }
            List<EquipmentType> kept = new ArrayList<>();
            Map<String, Integer> named = new HashMap<>();
            for (EquipmentType type : all) {
                if (((type instanceof WeaponType) || (type instanceof MiscType)) && seen.add(type.getInternalName())) {
                    kept.add(type);
                    named.merge(type.getName(), 1, Integer::sum);
                }
            }
            for (EquipmentType type : kept) {
                // The Inner Sphere and Clan versions often share a name, which the dialog's table tells apart
                String label = (named.get(type.getName()) > 1) ? Messages.getString(
                      "Lobby.requisition.advanced.equipmentBase", type.getName(),
                      type.isClan() ? "Clan" : "IS", type.getInternalName()) : type.getName();
                types.add(new EquipmentChoice(type.getInternalName(), label,
                      (type instanceof WeaponType) ? EquipmentGroup.WEAPON : EquipmentGroup.EQUIPMENT));
            }
            types.sort(Comparator.comparing(EquipmentChoice::label, String.CASE_INSENSITIVE_ORDER)
                  .thenComparing(EquipmentChoice::key));
            choices.addAll(types);
            equipmentChoices = List.copyOf(choices);
        }
        return equipmentChoices;
    }

    // ---- Building MegaMek's filters ----

    private static @Nullable MekSearchFilter totalWarfareFilter(Map<Field, Value> values) {
        boolean any = values.keySet().stream()
              .anyMatch(field -> (field.section() == Section.TOTAL_WARFARE) && (field != Field.RUN));
        if (!any) {
            return null;
        }
        MekSearchFilter filter = MekSearchFilter.allowingAll();
        values.forEach((field, value) -> {
            if (field.section() == Section.TOTAL_WARFARE) {
                applyTotalWarfare(filter, field, value);
            }
        });
        return filter;
    }

    private static void applyTotalWarfare(MekSearchFilter filter, Field field, Value value) {
        switch (value) {
            case Range range -> {
                String min = (range.min() == null) ? "" : String.valueOf(range.min());
                String max = (range.max() == null) ? "" : String.valueOf(range.max());
                switch (field) {
                    case WALK -> {
                        filter.sStartWalk = min;
                        filter.sEndWalk = max;
                    }
                    case JUMP -> {
                        filter.sStartJump = min;
                        filter.sEndJump = max;
                    }
                    case TONS -> {
                        filter.sStartTons = min;
                        filter.sEndTons = max;
                    }
                    case YEAR -> {
                        filter.sStartYear = min;
                        filter.sEndYear = max;
                    }
                    case BV -> {
                        filter.sStartBV = min;
                        filter.sEndBV = max;
                    }
                    default -> { }
                }
            }
            case Level level -> filter.iArmor = level.index();
            case Flag flag -> setFlag(filter, field, flag.yes() ? 1 : 2);
            case Picks picks -> applyPicks(filter, field, picks);
            case Equipment equipment -> applyEquipment(filter, equipment);
            default -> { }
        }
    }

    private static void setFlag(MekSearchFilter filter, Field field, int state) {
        switch (field) {
            case OMNI -> filter.iOmni = state;
            case MILITARY -> filter.iMilitary = state;
            case INDUSTRIAL -> filter.iIndustrial = state;
            case CLAN_ENGINE -> filter.iClanEngine = state;
            case PATCHWORK -> filter.iPatchwork = state;
            case SUPPORT_VEHICLE -> filter.iSupportVehicle = state;
            case MOUNTED_INFANTRY -> filter.iMountedInfantry = state;
            case WATER_ONLY -> filter.iWaterOnly = state;
            case FRANKEN_MEK -> filter.iFrankenMek = state;
            case OFFICIAL -> filter.iOfficial = state;
            case INVALID -> filter.iInvalid = state;
            case FAILED_EQUIPMENT -> filter.iFailedToLoadEquipment = state;
            case DOOMED_ON_GROUND -> filter.iDoomedOnGround = state;
            case DOOMED_IN_ATMOSPHERE -> filter.iDoomedInAtmosphere = state;
            case DOOMED_IN_SPACE -> filter.iDoomedInSpace = state;
            case DOOMED_IN_EXTREME_TEMP -> filter.iDoomedInExtremeTemp = state;
            case DOOMED_IN_VACUUM -> filter.iDoomedInVacuum = state;
            default -> { }
        }
    }

    private static void applyPicks(MekSearchFilter filter, Field field, Picks picks) {
        switch (field) {
            case UNIT_TYPE -> {
                picks.in().forEach(type -> setUnitType(filter, (String) type, 1));
                picks.out().forEach(type -> setUnitType(filter, (String) type, 2));
            }
            case TECH_BASE -> addStrings(picks, filter.techBase, filter.techBaseExclude);
            case MOVE_MODE -> addStrings(picks, filter.moveModes, filter.moveModeExclude);
            case TECH_LEVEL -> addCodes(picks, filter.techLevel, filter.techLevelExclude);
            case ARMOR_TYPE -> addCodes(picks, filter.armorType, filter.armorTypeExclude);
            case INTERNALS -> addCodes(picks, filter.internalsType, filter.internalsTypeExclude);
            case ENGINE -> addCodes(picks, filter.engineType, filter.engineTypeExclude);
            case COCKPIT -> addCodes(picks, filter.cockpitType, filter.cockpitTypeExclude);
            case GYRO -> addCodes(picks, filter.gyroType, filter.gyroTypeExclude);
            case QUIRKS -> {
                addStrings(picks, filter.quirkType, filter.quirkTypeExclude);
                filter.quirkInclude = picks.any() ? 1 : 0;
                filter.quirkExclude = 1;
            }
            case WEAPON_QUIRKS -> {
                addStrings(picks, filter.weaponQuirkType, filter.weaponQuirkTypeExclude);
                filter.weaponQuirkInclude = picks.any() ? 1 : 0;
                filter.weaponQuirkExclude = 1;
            }
            default -> { }
        }
    }

    private static void addStrings(Picks picks, List<String> in, List<String> out) {
        picks.in().forEach(value -> in.add((String) value));
        picks.out().forEach(value -> out.add((String) value));
    }

    private static void addCodes(Picks picks, List<Integer> in, List<Integer> out) {
        picks.in().forEach(value -> in.add((Integer) value));
        picks.out().forEach(value -> out.add((Integer) value));
    }

    private static void setUnitType(MekSearchFilter filter, String type, int state) {
        switch (type) {
            case "Mek" -> filter.filterMek = state;
            case "BipedMek" -> filter.filterBipedMek = state;
            case "ProtoMek" -> filter.filterProtomek = state;
            case "LAM" -> filter.filterLAM = state;
            case "Tripod" -> filter.filterTripod = state;
            case "Quad" -> filter.filterQuad = state;
            case "QuadVee" -> filter.filterQuadVee = state;
            case "Aero" -> filter.filterAero = state;
            case "AerospaceFighter" -> filter.iAerospaceFighter = state;
            case "FixedWingSupport" -> filter.filterFixedWingSupport = state;
            case "ConvFighter" -> filter.filterConvFighter = state;
            case "SmallCraft" -> filter.filterSmallCraft = state;
            case "Dropship" -> filter.filterDropship = state;
            case "Jumpship" -> filter.filterJumpship = state;
            case "Warship" -> filter.filterWarship = state;
            case "SpaceStation" -> filter.filterSpaceStation = state;
            case "Infantry" -> filter.filterInfantry = state;
            case "BattleArmor" -> filter.filterBattleArmor = state;
            case "Tank" -> filter.filterTank = state;
            case "VTOL" -> filter.filterVTOL = state;
            case "SupportVTOL" -> filter.filterSupportVTOL = state;
            case "GunEmplacement" -> filter.filterGunEmplacement = state;
            case "SupportTank" -> filter.filterSupportTank = state;
            case "LargeSupportTank" -> filter.filterLargeSupportTank = state;
            case "SuperHeavyTank" -> filter.filterSuperHeavyTank = state;
            default -> { }
        }
    }

    private static void applyEquipment(MekSearchFilter filter, Equipment equipment) {
        if (!equipment.complete()) {
            return;
        }
        try {
            List<FilterToken> tokens = new ArrayList<>();
            for (Term term : equipment.terms()) {
                tokens.add(FilterToken.parse(term.token()));
            }
            filter.createFilterExpressionFromTokens(tokens);
        } catch (FilterParsingException | com.fasterxml.jackson.core.JsonParseException failure) {
            LOGGER.warn("The equipment expression {} could not be read", equipment.text());
        }
    }

    private static ASSearchFilter alphaStrikeFilter(Map<Field, Value> values) {
        ASSearchFilter.Builder filter = ASSearchFilter.builder();
        values.forEach((field, value) -> {
            switch (value) {
                case Picks picks when field == Field.AS_TYPE -> filter.unitTypes(typed(picks, ASUnitType.class));
                case Picks picks when field == Field.AS_ROLE -> filter.roles(typed(picks, UnitRole.class));
                case Picks picks when field == Field.AS_SIZE -> filter.sizes(typed(picks, Integer.class));
                case Picks picks when field == Field.AS_TMM -> filter.tmms(typed(picks, Integer.class));
                case Picks picks when field == Field.AS_OV -> filter.ovs(typed(picks, Integer.class));
                case Picks picks when field == Field.AS_SPECIALS ->
                      typed(picks, BattleForceSUA.class).forEach(filter::ability);
                case Range range when field == Field.AS_ARMOR -> filter.armor(asRange(range));
                case Range range when field == Field.AS_STRUCTURE -> filter.structure(asRange(range));
                case Range range when field == Field.AS_THRESHOLD -> filter.threshold(asRange(range));
                case Range range when field == Field.AS_PV -> filter.pointValue(asRange(range));
                case Range range when field == Field.AS_DAMAGE_S -> filter.damageS(damage(range));
                case Range range when field == Field.AS_DAMAGE_M -> filter.damageM(damage(range));
                case Range range when field == Field.AS_DAMAGE_L -> filter.damageL(damage(range));
                case Range range when field == Field.AS_DAMAGE_E -> filter.damageE(damage(range));
                case Movement movement -> filter.movement(new ASSearchFilter.Movement(movement.mode(),
                      asRange(movement.range())));
                default -> { }
            }
        });
        return filter.build();
    }

    private static ASSearchFilter.IntRange asRange(Range range) {
        return ASSearchFilter.IntRange.of(range.min(), range.max());
    }

    private static ASSearchFilter.DamageRange damage(Range range) {
        List<megamek.common.alphaStrike.ASDamage> damage = ASSearchFilter.DAMAGE_VALUES;
        return new ASSearchFilter.DamageRange(damage.get(clampIndex(range.min(), 0)),
              damage.get(clampIndex(range.max(), damage.size() - 1)));
    }

    private static BFSSearchFilter battlefieldSupportFilter(Map<Field, Value> values) {
        BFSSearchFilter.Builder filter = BFSSearchFilter.builder();
        values.forEach((field, value) -> {
            switch (value) {
                case Picks picks when field == Field.BF_TYPE -> filter.assetTypes(typed(picks, BFSAssetType.class));
                case Picks picks when field == Field.BF_MOVE_MODE ->
                      filter.moveModes(typed(picks, EntityMovementMode.class));
                case Picks picks when field == Field.BF_SPECIALS -> filter.specials(typed(picks, BFSSpecialType.class));
                case Picks picks when field == Field.BF_ARTILLERY ->
                      filter.artilleryTypes(typed(picks, BFSArtilleryType.class));
                case Minimums minimums -> minimums.values()
                      .forEach((type, minimum) -> filter.numericSpecial((BFSSpecialType) type, minimum));
                case Range range when field.section() == Section.BATTLEFIELD_SUPPORT ->
                      filter.range(stat(field), BFSSearchFilter.IntRange.of(range.min(), range.max()));
                default -> { }
            }
        });
        return filter.build();
    }

    private static BFSSearchFilter.Stat stat(Field field) {
        return switch (field) {
            case BF_MP -> BFSSearchFilter.Stat.MP;
            case BF_TMM -> BFSSearchFilter.Stat.TMM;
            case BF_SKILL -> BFSSearchFilter.Stat.SKILL;
            case BF_DAMAGE -> BFSSearchFilter.Stat.DAMAGE;
            case BF_DAMAGE_PER_HIT -> BFSSearchFilter.Stat.DAMAGE_PER_HIT;
            case BF_DAMAGE_HITS -> BFSSearchFilter.Stat.DAMAGE_HITS;
            case BF_LONG_RANGE -> BFSSearchFilter.Stat.LONG_RANGE;
            case BF_DESTROY_CHECK -> BFSSearchFilter.Stat.DESTROY_CHECK;
            case BF_THRESHOLD -> BFSSearchFilter.Stat.THRESHOLD;
            case BF_BSP -> BFSSearchFilter.Stat.BSP;
            case BF_BV -> BFSSearchFilter.Stat.BV;
            default -> throw new IllegalArgumentException(field.name());
        };
    }

    private static <T> Set<T> typed(Picks picks, Class<T> type) {
        Set<T> set = new HashSet<>();
        for (Object value : picks.in()) {
            set.add(type.cast(value));
        }
        return set;
    }

    /** A criterion's value from a view's text field: a whole number, or null when blank or not a number. */
    public static @Nullable Integer number(@Nullable String text) {
        if ((text == null) || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Equal criteria by value, for tests. */
    static boolean sameValues(RequisitionAdvancedSearch a, RequisitionAdvancedSearch b) {
        return Objects.equals(a.values, b.values);
    }
}
