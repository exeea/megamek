
This readme describes the board editor. It also contains information on the
board terrain values, the old .board file format and the tileset file format.

  Introduction To The Board Editor
    You can open the board editor from the main menu. It shows the board in
    3D: a new, blank 16x17 board, or the board you chose. The default size for
    map boards in MegaMek is 16x17. Most paper Battletech boards are about
    15x17 hexes, but this does not allow adjacent boards to line up properly
    on the computer.

    The Menu button at the top right has New, Open, Save, Save As, Save As
    Image, Recent Boards, Open Board Source File, Run Board Tagger, the Legacy
    2D Preview (the board as the Legacy 2D board draws it), View (with the
    Renderer and the Trace Overlay), Client Settings and Help. Help lists every key and mouse gesture
    of the editor.

    The editor saves boards as .board2 files. Opening an older .board file
    converts it: its decorative scenery becomes 3D objects, and Save writes a
    new .board2 file next to it, keeping the original file. The MegaMek server
    loads both kinds of board file from the data/boards directory. A board
    that breaks the game's rules can still be saved; the editor lists its
    problems under the red warning triangle at the top, and games refuse the
    board until they are fixed.

  Quick Reference
    The tools are Select (Z), Sculpt (X), Paint (C) and Erase (V).
    Left mouse button - select a hex or object, or use the tool on the hexes
                        within the brush radius
    Left drag - select with a box, move the selection, or paint along the drag
    CTRL + mouse wheel - raise or lower the hovered hex
    CTRL + Z / CTRL + Y - undo / redo
    CTRL + C / CTRL + V - copy and paste a hex (also between MegaMek windows)
    Right drag - move the view; mouse wheel - zoom
    ALT + left mouse - measure line of sight

  Painting Terrain
    Assets on the left holds the terrain recipes (woods, water, roads,
    buildings and more), structures, props and decals. Choose one, then click
    or drag on the board to paint it. The Brush panel at the bottom left holds
    the options of the active tool: the radius, the values of the terrain you
    paint, and "Only overwrite clear hexes", which leaves every hex that
    already holds terrain as it is. Sculpt raises, lowers or levels the
    ground; Erase removes what the active brush paints.

  Editing A Hex
    Each hex has an elevation and may have terrain. A clear hex has no terrain
    listed. Click a hex with the Select tool: Layers on the right lists its
    terrain components and objects, and Edit below it changes the selected
    one, with every terrain value the game uses and, for terrain with exits,
    the hex sides it connects to. "Use as brush" makes the whole hex the brush.
    Hold the pointer over a Layers row to see its terrain as the board file
    writes it, in the format "terrain:level" or "terrain:level:exits".

    See the list below for details on the types of terrain and the levels that
    the game expects them at.

    The exits of most terrain are set by the game: a side connects when the
    hex on that side has the same terrain. A board can also specify them, as
    described for the board file format below.

  Board Settings
    Settings at the top right holds the board's own settings (Arena, Exit Roads
    to Pavement, tags), commands for the whole board (theme, raise or lower,
    flatten, flood, remove a terrain, clear) and its size (resize, new board,
    random map).

    Normally, roads automatically exit onto all adjacent pavement hexes. The
    designer of the board can override this by explicitly specifying the exits
    of the road hex. On some boards, however, it is easier to specify the road
    hexes that *do* exit onto a pavement hex rather than saying which ones do
    *not* exit onto the pavement. For those boards, uncheck "Exit Roads to
    Pavement" in Settings. The board then saves the "exit_roads_to_pavement"
    option described below as "false". Please note that, when multiple boards
    are used in a game, if one board has the "exit_roads_to_pavement" option
    set to "false", *NO* roads will automatically exit onto a pavement hex, so
    use this option sparingly.

  Tracing A Paper Map
    The Trace Overlay (Menu, View) lays a reference image, such as a scanned
    paper map, over the board while you look straight down: use the top view
    button at the top right. Choose the image, its opacity, scale and position
    in Client Settings, Overlays. The position is in pixels of the 2D board at
    100% zoom, where a hex is 84 x 72 pixels; a position of 84, 72 puts the
    image's top left corner on the first hex's corner. The image is never
    saved with the board.

  Types Of Terrain Features
    The game expects the level for each type of terrain feature within a certain
    range.  Values not in this range may cause the terrain to appear on the map,
    but there will usually be no game effect, or, worse, undesirable effects.
    A lot of terrain is activated with a value of "1".

    Some types of terrain should only appear in combination with other types.
    The bldg_cf, bldg_elev, and bldg_basement should only appear in a hex that
    has a building level > 0.  The swamp terrain should only appear in a clear,
    rough, or woods hex.

    The "fluff" terrain type placed special images on the map board of older
    .board files that have no effect on play, but improve the overall
    appearance of the map.  The level of fluff in the terrain, coupled with
    the exits that are specified for the fluff, determine the image that
    appears.  The full list of fluff images, and the level and exits needed
    to display them, can be seen in the file,
    "data/images/hexes/atmospheric.tileset".  The board editor converts this
    scenery into 3D objects and decals, which it places directly instead.

    The terrain feature types and their expected values:

        woods: 1-3; 1 for light woods, 2 for heavy woods, 3 for ultra-heavy woods
        rough: 1-2; 1 for normal rough, 2 for ultra-rough
        rubble: 1-6; 1-4 corresponding to building types, 5 for wall rubble, 6 for ultra rubble
        water: 0+; the hex elevation is the elevation for the surface of the
            water, and the water level is the depth of the water
        pavement: 1
        road: 1-4; 1 for normal, 2 for alley (legacy), 3 for dirt, 4 for gravel
        fire: 1-2; 1 for normal fire, 2 for inferno fire
        smoke: 1-2; 1 for light smoke, 2 for heavy smoke
        swamp: 1-3; 1 for normal, 2 for just became quicksand, 3 for quicksand
        building: 1-4; 1 = light ... 4 = hardened
        bldg_cf: 0-150; defaults to 15, 40, 90, or 120 if not specified
        bldg_elev: 1+; you must supply a number if a building is supplied.
        bldg_basement_type: 0-7, indicates type of basement and type of fall
            0: unknown basement, game will roll the type of basement when entering first
            1: no basement
            2: double basement, feet first fall
            3: single basement, feet first fall
            4: single basement, normal fall
            5: small basement
            6: single basement, head first fall
            7: double basement, head first fall

        bldg_class: 0-3; 0 for standard, 1 for hangar, 2 for fortress, 3 for gun emplacement,
                         defaults to standard if not specified
        bldg_armor: 0-150
        bridge: 1-4 (not functional in 0.26); 1 = light ... 4 = reinforced
        bridge_cf: 0-150; defaults to 15, 40, 90, or 120 if not specified
        bridge_elev: any; surface of the bridge, defaults to 0 if not present
        fluff: 1-9; see the notes above.
        arms: 1-2; blown off limbs
        legs: 1-2; blown off limbs
        ice: 1; combine with water to make "thin ice"
        fortified: 1
        geyser: 1-3; 1 is dormant, 2 is active, 3 is a magma vent
        magma: 1-2; 1 is crust, 2 is liquid
        jungle: 1-3; as woods
        mud: 1
        rapids: 1-2; 1 for rapids, 2 for torrents (does not include water flow)
        sand: 1
        snow: 1-2; 1 for thin snow, 2 for deep snow
        tundra: 1
        fields: 1; for planted fields
        industrial: 1+; level indicates the height of the heavy industrial terrain
        impassable: 1; this prevents units entering or deploying, e.g. for underground
        elevator: (any); each exit corresponds to a die roll it will move on, and the
                         terrain level is the new elevation after it moves.


The old .board file format is plain text.  You will probably not need to edit
the board files by hand.  This reference is just for creating a different
editor.  The board editor reads it and writes the .board2 format, a JSON
document that also holds the 3D objects and appearance of each hex.

  The Board File Format
    As mentioned earlier, the server scans for files that have a .board
    extension, in the data/boards directory.

    Board files consist of a keyword, usually followed by several parameters.
    The four keywords used are "size", "option", "hex", and "end".  Keywords
    should begin the line of text they are on.  Parameters are separated by a
    space.  String parameters with a space in them, or empty string parameters
    can be quoted.  Comment lines are preceded by the hash (#) character.

  The "size" Keyword
    The size keyword is followed by the width and the height of the board, in
    hexes.  The default size is 16x17.  This would appear as "size 16 17".

  The "option" Keyword
    The option keyword is followed by the name of the option and the option's
    value for the board.  In v0.29, the only option recognized by MegaMek is
    exit_roads_to_pavement.  If this option is set to false, road hexes will
    not automatically exit into pavement hexes.  If a game's playing area uses
    more than one board, if any board sets this option to false, no road hexes
    on *any* board will automatically exit into pavement hexes.

  The "end" Keyword
    The end keyword indicates the end of data.  It has no parameters and appears
    on a line by itself.

  The "hex" Keyword
    The board data is indicated by the hex keyword, which should appear between
    the size and end lines.  The format goes:
        hex <coordinates> <elevation> <terrain attributes> <theme>

    The coordinates should be as they appear on paper mapsheets: a four-digit
    number starting with 0101 in the upper left-hand corner and going as high
    as 1617 for a board of the default size.

    The elevation should be an integer, unquoted.

    All terrain attributes, if any, for the hex should be a single, quoted
    string.  Within this string, terrain is specified as:
        <terrain>:<level>[:<exits>]

    The terrain should be a lower-case string of one of the terrain types
    listed above.  The level is separated by the terrain by a colon (:).
    Optionally, the exits parameter is appended, separated with a : again.

    Multiple terrain types should be in the same string, separated with the
    semicolon (;) character.

    The theme of the hex is purely for cosmetic purposes.  It should be left
    blank for ordinary hexes by putting empty quotes in its place.  The theme
    is intended as a tag for the tileset file to indicate a special graphic for
    the hex.  Its uses might include indicating a specific graphic for a
    building, or making rivers distinct from lakes.  In v0.29, only the "snow"
    theme is available.  Note, the "snow" theme does *not* implement the "ice"
    or "cold weather" rules from the BattleTech Master Rules; they just look
    like they should.

    Hex data can appear in any order, as long as there is only one hex at any
    specific coordinates.  Any coordinates with no data will be filled in with
    level 0, clear terrain.

  The "description" Keyword
    A human-readable description of the map can be given with the "description"
    keyword. Each entry is a new paragraph in the description. The format goes:
        description "quoted text for the description"

  The "note" Keyword
    Certain hexes may have interesting features that should be called out,
    especially to users who are visually impaired. The "note" keyword allows
    annotations to be applied per hex. The format goes:
        note <coordinates> "quoted text for the annotation"

    Each hex can have multiple annotations.

The graphics for maps are stored in tileset files.  These are located in the
data/images/hexes directory.  The default tileset is called "atmospheric.tileset".
Image files should be located in a subdirectory off of the data/images/hexes directory.
The tileset used by the game can be changed in the MegaMek.cfg file, in the
game base directory.

  Tileset File Format
    The tileset file format is similar to the board file format.  The two
    keywords used are "super" and "base".  Both take the same parameters.
    All super lines should appear before all base lines.  The order of lines,
    even within these groupings, is significant.

  The "super" Keyword
    Lines beginning with the super keyword indicate graphics that will be
    displayed on top of, superimposed over, a base graphic for the hex.

  The "base" Keyword
    Lines beginning with the base keyword indicate a graphic that will be used
    as the base graphic for the hex.

  Parameters
    The parameters are similar to the parameters for hex data in the board
    files.  The format is:
        super/base <elevation> <terrains> <theme> <image file>

    Elevation, terrains and theme are specified in the same manner as in the
    board file.  Elevation can use "*" as a wildcard to indicate that the
    graphic can be used for any elevation.

    The image file should be the path and name of the image, with the path
    originating in the data/hexes directory.  Thus, if you wanted to load
    "rubble.gif" out of the "myhexes" subdirectory, put "myhexes/rubble.gif".

  Matching Tileset Graphics With Actual Hexes
    First, let's talk about base hexes.  When trying to figure out a base
    graphic for a hex on the board, the program goes through all the base hex
    images in the tileset in the order that they are listed.  The first, best
    match is used.  That is, the first base hex image that best matches the hex
    on the board.

    Super images are listed first so that they can be matched first.  They match
    a little differently than base hexes, in that they must have an exact match
    for any elevation, terrain, or theme listed for that image in the tileset.
    Any terrain matched exactly in this manner is then removed from
    consideration for future matches.

    As an example of the matching process, take a hex on the board, at level 2,
    and with woods and road.  This hex would appear in the board file as:
        hex 0205 2 "woods:1;road:1" ""
    Let's also assume that this road is part of a larger road, running north to
    south, so the program internally determines the road's exits parameter to
    be 9.

    The relevant entries from the default tileset are:
        super * "road:1:09" "" "boring/road09.gif"
        base 2 "woods:1" "" "boring/light_forest_2.gif"
    Both are perfect matches when evaluated by the program.

    Some images require two or more terrains to be used in tandem.  Take, for
    example, the "light_woods_swamp_1.gif" image.  This is the image that would
    be used for the following hex:
        hex 0205 1 "woods:1;swamp:1" ""

    The relevant entry from the default tileset is:
        base 1 "woods:1;swamp:1" "" "swamp/light_forest_swamp_1.gif"

    For the super image, the elevation is a match, because it's a wildcard.
    The hex being evaluated contains a perfect match for all terrain specified
    by the super image, and the theme is a match because both are blank.
    After making this match, the road terrain in the hex being evaluated is
    removed from further evaluation.

    After this removal, the woods terrain is a perfect match.  All the relevant
    terrain, the elevation and the theme are matches.  The program will display
    the light_forest_2.gif, with the road09.gif superimposed on top of it, as
    the image for this hex in the game.


Author: Ben Mazur
