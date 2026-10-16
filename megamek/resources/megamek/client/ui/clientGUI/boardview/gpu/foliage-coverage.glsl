// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The outputs terrain-foliage.frag's alpha test reads, invariant in a cutout's vertex stage as they are there: a colour
// program and its depth twin (GpuTerrain.depthPrepass) compute them identically, so both keep the same texels.
#ifdef diffuseTextureFlag
invariant v_diffuseUV;
#endif
#ifdef colorFlag
invariant v_color;
#endif
#ifdef blendedFlag
invariant v_opacity;
#ifdef alphaTestFlag
invariant v_alphaTest;
#endif
#endif
