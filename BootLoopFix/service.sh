#!/system/bin/sh
MODDIR=${0%/*}
sleep 15
BAD_PKG="com.example.badservice"
if [ -d "/data/data/$BAD_PKG" ]; then
    pm disable "$BAD_PKG"
    echo "تم تعطيل الحزمة المسببة للمشكلة: $BAD_PKG" >> "$MODDIR/boot_fix.log"
fi
if [ -f "/cache/bootloop_flag" ]; then
    rm -f "/cache/bootloop_flag"
    touch "$MODDIR/disable"
fi