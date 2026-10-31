#!/system/bin/sh
ui_print "Installing Magisk AdBlocker..."
if [ -f "$MODPATH/system/etc/hosts" ]; then
  cp -f "$MODPATH/system/etc/hosts" "/system/etc/hosts"
  set_perm "/system/etc/hosts" 0 0 0644
fi
ui_print "Done."
