#!/system/bin/sh
# CarPlay Ultra-Lite: what the Vehicle page needs root for. CarPlay's receiver (DiPO, uid system) can't read or
# write other apps' files, so this service (root) does, once a second:
# - the ambient light switch: "1" or "0" left in cpc-lights-req goes into Light Vibes' "enabled" setting, which
#   its daemon picks up within half a second; the current state goes back in cpc-lights-state ("1", "0", or
#   "none" when Light Vibes has no settings folder yet: open its app once).
# - battery preconditioning: whether Launchpad holds a manual request (its own "requested" record) goes back in
#   cpc-precond-state ("1", "0", or "none" without Launchpad). Read only: Launchpad itself sends every command.
# - adb over USB after a CarPlay connect (setting adb_keepalive, a test aid): DiPO leaves cpc-adb-req at each
#   CarPlay session; 12 s later the port's live mode goes to host and back to adb, as the ADB mod's "ADB over
#   USB" switch does when turned off and on (sys.usb.config; the saved persist. copy is not touched).
# - filtered climate/video diagnostics copied to the registered USB log folder every ten seconds.
# It writes one file of Light Vibes, that one property, and the diagnostic files; no vehicle commands.
DIPO=/data/data/com.daudio.app.dipo/files
REQ=$DIPO/cpc-lights-req
STATE=$DIPO/cpc-lights-state
PRECOND=$DIPO/cpc-precond-state

# the Ioniq 6 port (NEMESIS package ambient-sound), then the HMG-API mod
settings() {
    for d in /data/data/org.gen5w.ambientsound/files/settings /data/data/lightvibes.app/files/settings; do
        [ -d $d ] && { echo $d; return; }
    done
}

# 1 while Launchpad has recorded a request it owns, 0 if not, none without Launchpad
launchpad() {
    for p in com.paytondev.launchpad com.paytondev.precondition; do
        [ -d /data/data/$p ] || continue
        if grep -q 'name="requested" value="true"' /data/data/$p/shared_prefs/launchpad_preconditioning.xml 2>/dev/null; then
            echo 1
        else
            echo 0
        fi
        return
    done
    echo none
}

# writes a state file for DiPO when it changed
report() {
    [ -d $DIPO ] || return
    echo $2 > $1
    chmod 644 $1
}

ADBREQ=$DIPO/cpc-adb-req
USBSTATE=/sys/class/android_usb/android0/state
usbstate() { u=unknown; [ -f $USBSTATE ] && read u < $USBSTATE; echo $u; }
usbtoggle() {
    echo "adb $1: usb $(usbstate), config $(getprop sys.usb.config); switching"
    setprop sys.usb.config host,adb
    sleep 2
    setprop sys.usb.config adb
    sleep 5
    echo "adb after: usb $(usbstate), config $(getprop sys.usb.config)"
}

last=
lastp=
logtick=0
while true; do
    lv=$(settings)
    if [ -f $REQ ]; then
        v=
        read v < $REQ
        rm -f $REQ
        if [ "$v" != 0 ] && [ "$v" != 1 ]; then
            echo "ignored request '$v'"
        elif [ -n "$lv" ]; then
            echo $v > $lv/enabled
            echo "lights $v"
        else
            echo "lights $v asked, but Light Vibes has no settings folder"
        fi
    fi
    if [ -f $ADBREQ ]; then
        rm -f $ADBREQ
        # in the background: the switches above keep answering meanwhile
        ( sleep 12; usbtoggle "CarPlay connected" ) &
    fi
    s=none
    if [ -n "$lv" ]; then
        s=1
        [ -f $lv/enabled ] && read s < $lv/enabled
    fi
    if [ "$s" != "$last" ]; then
        report $STATE $s
        last=$s
    fi
    p=$(launchpad)
    if [ "$p" != "$lastp" ]; then
        report $PRECOND $p
        echo "launchpad request $p"
        lastp=$p
    fi
    if [ "$logtick" = 0 ]; then
        sh "$HMGAPI_MOD_DIR/svc/export-log.sh"
    fi
    logtick=$(((logtick + 1) % 10))
    sleep 1
done
