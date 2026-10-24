#!/bin/bash
# Interleaved A/B of sample builds on one phone, bench of testbench/: each block reinstalls one build, connects, runs
# the scenarios, collects the RESULT lines. The order of the builds turns by one every round, so each build takes every
# position (the radio drifts during a session: never compare builds that always run in the same position).
#
#   DEVICE=<adb serial> NAME=<run name> APK_DIR=<dir of sample-<version>.apk> VERSION_LIST="3.0.5 4.0" \
#   SCENARIOS="Write 256 KB without response;OTA Silicon Labs 256 KB" ONLY_40="KnBle OTA: Silicon Labs 256 KB" \
#   [BENCH=pc] [OUT_DIR=.] ab.sh ROUNDS
#
# SCENARIOS: labels of the sample buttons, run in order. ONLY_40: scenarios of the 4.x builds only (versions starting
# with "4."). BENCH=pc: the bench found by its service UUID (PC, phone or ESP32-C3 bench), else the classic ESP32 by name.
# Output: OUT_DIR/ab_NAME.txt ("# time block version round" lines, then version<TAB>round<TAB>scenario<TAB>values) and
# the logcat of the sample in OUT_DIR/logcat_NAME.txt. Summary: python -I summary.py OUT_DIR/ab_NAME.txt
ADB="${ADB:-${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb} -s $DEVICE"
OUT_DIR="${OUT_DIR:-.}"
F="$OUT_DIR/logcat_$NAME.txt"
OUT="$OUT_DIR/ab_$NAME.txt"
RESULTS=/sdcard/Android/data/ovh.karewan.knble.sample/files/results.txt
: > "$OUT"

$ADB logcat -c
$ADB logcat -v threadtime -s KnBleSample:I > "$F" 2>&1 &
LOGCAT=$!
trap 'kill $LOGCAT 2>/dev/null' EXIT

# Starts a scenario and waits for a new log line matching $3 (at most $4 s)
start() {
	local before=$(grep -c "$3" "$F")
	local bench=""
	[ "$BENCH" = "pc" ] && bench="--es bench pc"
	$ADB shell "am start -n ovh.karewan.knble.sample/.MainActivity --es run '$2' $bench" >/dev/null 2>&1
	for i in $(seq 1 "$4"); do
		sleep 1
		[ $(grep -c "$3" "$F") -gt $before ] && return 0
	done
	echo "$1 TIMEOUT $2" | tee -a "$OUT"
	return 1
}

read -ra versions <<< "${VERSION_LIST:-3.0.5 4.0}"
n=${#versions[@]}
for round in $(seq 1 "${1:-3}"); do
	for k in $(seq 0 $((n - 1))); do
		v=${versions[$(((k + round - 1) % n))]}
		start "$v" "Disconnect the bench" "Disconnect:" 15
		sleep 1
		$ADB install -r -g "$APK_DIR/sample-$v.apk" >/dev/null 2>&1
		MSYS_NO_PATHCONV=1 $ADB shell rm -f "$RESULTS"
		$ADB shell input keyevent KEYCODE_WAKEUP
		sleep 2
		echo "# $(date +%H:%M:%S) block $v round $round" | tee -a "$OUT"
		start "$v" "Connect the bench" "Services:" 60
		sleep 1
		IFS=';' read -ra list <<< "$SCENARIOS"
		for sc in "${list[@]}"; do start "$v" "$sc" "■ " 400; done
		if [ "${v:0:2}" = "4." ] && [ -n "$ONLY_40" ]; then
			IFS=';' read -ra extra <<< "$ONLY_40"
			for sc in "${extra[@]}"; do start "$v" "$sc" "■ " 400; done
		fi
		MSYS_NO_PATHCONV=1 $ADB shell cat "$RESULTS" | tr -d '\r' | awk -F'\t' -v v="$v" -v r="$round" 'NF >= 5 { print v "\t" r "\t" $4 "\t" $5 }' | tee -a "$OUT"
	done
done
start "end" "Disconnect the bench" "Disconnect:" 15
echo "done" >> "$OUT"
