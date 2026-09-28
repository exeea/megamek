// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Inserted at the start of libGDX's fragment main; its lighting reads use these cloud-adjusted values.
#ifdef lightingFlag
float cloudLight = 1.0;
#ifdef cloudShadowFlag
cloudLight = cloudTransmission(v_cloudPosition);
#endif
vec3 cloudDiffuse = v_lightDiffuse * cloudLight;
#ifdef specularFlag
vec3 cloudSpecular = v_lightSpecular * cloudLight;
#endif
#if defined(ambientFlag) && defined(separateAmbientFlag)
vec3 cloudAmbient = v_ambientLight;
#ifdef normalFlag
cloudAmbient -= (1.0 - cloudLight) * v_groundBounce;
#endif
#endif
#endif
