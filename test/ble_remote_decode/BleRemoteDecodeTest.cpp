#include <gtest/gtest.h>

#include <cstdint>

#include "BleRemote.h"

using inklink::bleremote::decodeReport;

TEST(BleRemoteDecode, KeyboardArrowsAndPaging) {
  const uint8_t right[8] = {0, 0, 0x4F, 0, 0, 0, 0, 0};
  const uint8_t pageUp[8] = {0, 0, 0x4B, 0, 0, 0, 0, 0};
  const uint8_t enterSecondSlot[8] = {0x02, 0, 0x00, 0x28, 0, 0, 0, 0};
  EXPECT_EQ(decodeReport(right, 8), 1);
  EXPECT_EQ(decodeReport(pageUp, 8), -1);
  EXPECT_EQ(decodeReport(enterSecondSlot, 8), 1);
}

TEST(BleRemoteDecode, KeyboardReleaseAndUnknown) {
  const uint8_t none[8] = {};
  const uint8_t letterA[8] = {0, 0, 0x04, 0, 0, 0, 0, 0};
  EXPECT_EQ(decodeReport(none, 8), 0);
  EXPECT_EQ(decodeReport(letterA, 8), 0);
}

TEST(BleRemoteDecode, ConsumerShutterAndTracks) {
  const uint8_t volUp[2] = {0xE9, 0x00};
  const uint8_t volDown[2] = {0xEA, 0x00};
  const uint8_t prevTrack[3] = {0xB6, 0x00, 0x00};
  const uint8_t released[2] = {0x00, 0x00};
  EXPECT_EQ(decodeReport(volUp, 2), 1);
  EXPECT_EQ(decodeReport(volDown, 2), -1);
  EXPECT_EQ(decodeReport(prevTrack, 3), -1);
  EXPECT_EQ(decodeReport(released, 2), 0);
}

TEST(BleRemoteDecode, EmptyInput) {
  EXPECT_EQ(decodeReport(nullptr, 0), 0);
  const uint8_t one[1] = {0xE9};
  EXPECT_EQ(decodeReport(one, 1), 1);
}
