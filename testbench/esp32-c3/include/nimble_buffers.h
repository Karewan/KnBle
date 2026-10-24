// Pre-included before every source of the build (platformio.ini): the configuration of the Arduino core first, then
// more NimBLE buffers. A long write with a small MTU takes one buffer per prepared write: with the 12 of the core, 512
// bytes written while the MTU is 23 fail (the long characteristic, the reliable write of KnBle).
#pragma once
#include "sdkconfig.h"
#undef CONFIG_BT_NIMBLE_MSYS_1_BLOCK_COUNT
#define CONFIG_BT_NIMBLE_MSYS_1_BLOCK_COUNT 48
