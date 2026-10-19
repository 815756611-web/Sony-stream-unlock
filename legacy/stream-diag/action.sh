#!/system/bin/sh
# KernelSU / SukiSU action button: run a brief four-in-one check.
MODDIR=${0%/*}
sh "$MODDIR/tools/stream-diag.sh" --brief
