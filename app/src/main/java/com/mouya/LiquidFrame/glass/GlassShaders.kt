package com.mouya.LiquidFrame.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object GlassShaders {

    // Improved refraction shader with depth effect support (from AndroidLiquidGlass-kmp)
    const val REFRACTION = """
uniform shader content;

uniform float2 uSize;
uniform float2 uOffset;
uniform float4 uCornerRadii;
uniform float  uRefractionHeight;
uniform float  uRefractionAmount;
uniform float  uDepthEffect;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else                return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else                return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float  outside     = length(max(cornerCoord, 0.0)) - radius;
    float  inside      = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize      = uSize * 0.5;
    float2 centeredCoord = (coord + uOffset) - halfSize;
    float  radius        = radiusAt(coord, uCornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= uRefractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float  d          = circleMap(1.0 - -sd / uRefractionHeight) * uRefractionAmount;
    float  gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad       = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + uDepthEffect * normalize(centeredCoord));

    return content.eval(coord + d * grad);
}"""

    // Improved highlight shader with falloff control
    const val HIGHLIGHT = """
uniform float2 uSize;
uniform float4 uCornerRadii;
uniform float  uAngle;
uniform float  uStrength;
uniform float  uFalloff;
layout(color) uniform half4 uColor;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else                return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else                return radii.w;
    }
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

half4 main(float2 coord) {
    float2 halfSize      = uSize * 0.5;
    float2 centeredCoord = coord - halfSize;
    float  radius        = radiusAt(centeredCoord, uCornerRadii);

    float  gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad       = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal     = float2(cos(uAngle), sin(uAngle));
    float  d          = dot(grad, normal);
    float  intensity  = pow(abs(d), uFalloff) * uStrength;
    return uColor * intensity;
}"""

    fun refraction(): RuntimeShader = RuntimeShader(REFRACTION)

    fun highlight(): RuntimeShader = RuntimeShader(HIGHLIGHT)
}