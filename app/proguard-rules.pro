# R8 rules for the release build.
#
# AMap ships plain JARs without consumer rules, so its keep rules go here. Source:
# 【官方文档】https://lbs.amap.com/api/android-sdk/guide/create-project/dev-attention ("代码混淆", read 2026-11-12;
# page last updated 2025-05-12). 3D map uses the "V5.0.0之后" block; location and search are not version-split.
# If a build reports warnings for another package, the same page says to add "-dontwarn <package>".

# 3D map (V5.0.0 and later)
-keep   class com.amap.api.maps.**{*;}
-keep   class com.autonavi.**{*;}
-keep   class com.amap.api.trace.**{*;}

# Location
-keep class com.amap.api.location.**{*;}
-keep class com.amap.api.fence.**{*;}
-keep class com.autonavi.aps.amapapi.model.**{*;}

# Search
-keep   class com.amap.api.services.**{*;}

# Classes AMap's location SDK references but does not ship (optional GNSS add-on and a math library).
# R8 reported them as missing; per the same page, warned packages get -dontwarn.
-dontwarn com.amap.ams.gnss.GnssSoftLocator
-dontwarn net.jafama.FastMath
