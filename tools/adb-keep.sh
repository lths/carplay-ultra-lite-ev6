#!/system/bin/sh
# Test aid for the head unit: brings adb over USB back after each CarPlay connect.
#
# Why: when CarPlay connects, the head unit's USB port stops answering adb. The fix by hand is HMG-API app >
# Mods > ADB > "ADB over USB" off, then on. That toggle sets the live property sys.usb.config to "host,adb"
# and back to "adb" (HMG-API mods/adb/patches.json). This script does the same two steps by itself.
#
# How it knows CarPlay connected: cp-climate writes "new session: fresh vehicle state" to HMG-API's log at
# every CarPlay session. The script counts those lines every 3 s.
#
# It changes sys.usb.config only (not the persist. copy), so a restart puts everything back as it was.
# It doesn't survive a restart either: start it again after each one.
#
# Run as root:  hmgapid sh "sh /data/local/tmp/adb-keep.sh >/data/local/tmp/adb-keep.log 2>&1 &"
# Stop:         hmgapid sh "touch /data/local/tmp/adb-keep.stop"
LOG=/data/hmgapi/logs/hmgapi/log.log
STOP=/data/local/tmp/adb-keep.stop
USB=/sys/class/android_usb/android0/state
WAIT=${1:-12}      # seconds after a CarPlay connect before the toggle

sessions() { grep -c "new session: fresh vehicle state" $LOG 2>/dev/null; }
state() { s=unknown; [ -f $USB ] && read s < $USB; echo $s; }

toggle() {
    echo "$(date '+%H:%M:%S') $1: usb $(state), config $(getprop sys.usb.config); toggling"
    setprop sys.usb.config host,adb
    sleep 2
    setprop sys.usb.config adb
    sleep 5
    echo "$(date '+%H:%M:%S') after: usb $(state), config $(getprop sys.usb.config)"
}

rm -f $STOP
last=$(sessions)
echo "$(date '+%H:%M:%S') started: $last CarPlay session(s) in the log so far, usb $(state), config $(getprop sys.usb.config)"
while [ ! -f $STOP ]; do
    n=$(sessions)
    if [ -n "$n" ] && [ "$n" != "$last" ]; then
        # a smaller count means the log was rotated: no new session
        if [ "$n" -gt "$last" ]; then
            sleep $WAIT
            toggle "CarPlay session $n"
            # once more if the port still isn't a working adb device (never more than twice per session)
            if [ "$(state)" != CONFIGURED ] && [ -f $USB ]; then
                sleep 10
                [ "$(state)" != CONFIGURED ] && toggle "second try"
            fi
            n=$(sessions)
        fi
        last=$n
    fi
    sleep 3
done
rm -f $STOP
echo "$(date '+%H:%M:%S') stopped"
