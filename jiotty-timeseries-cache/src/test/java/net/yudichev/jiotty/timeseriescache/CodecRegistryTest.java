package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodecRegistryTest {
    private static final JsonUtf8Codec JSON = new JsonUtf8Codec();
    private static final SmileCodec SMILE = new SmileCodec();
    private static final int V1 = 1;

    @ParameterizedTest
    @MethodSource("eachCodec")
    void roundTripPreservesSimplePojo(Codec writeCodec) {
        var registry = new CodecRegistry(writeCodec, List.of(JSON, SMILE));
        var value = new Sample("hello", 42, true);

        byte[] frame = registry.encode(Optional.of(value), V1);

        assertThat(registry.decode(frame, TypeToken.of(Sample.class), V1)).isEqualTo(new DecodeOutcome.Present<>(value));
    }

    @ParameterizedTest
    @MethodSource("eachCodec")
    void roundTripPreservesJdk8AndJavaTime(Codec writeCodec) {
        var registry = new CodecRegistry(writeCodec, List.of(JSON, SMILE));
        var value = new TemporalSample(Optional.of("present"), Optional.empty(), Instant.parse("2026-05-16T12:34:56Z"), LocalDate.of(2026, 5, 16));

        byte[] frame = registry.encode(Optional.of(value), V1);

        assertThat(registry.decode(frame, TypeToken.of(TemporalSample.class), V1)).isEqualTo(new DecodeOutcome.Present<>(value));
    }

    @ParameterizedTest
    @MethodSource("eachCodec")
    void roundTripPreservesGuavaImmutableCollections(Codec writeCodec) {
        var registry = new CodecRegistry(writeCodec, List.of(JSON, SMILE));
        var value = new CollectionSample(ImmutableList.of("a", "b", "c"), ImmutableMap.of("k1", 1, "k2", 2));

        byte[] frame = registry.encode(Optional.of(value), V1);

        assertThat(registry.decode(frame, TypeToken.of(CollectionSample.class), V1)).isEqualTo(new DecodeOutcome.Present<>(value));
    }

    @ParameterizedTest
    @MethodSource("eachCodec")
    void roundTripPreservesGenericTypeViaTypeToken(Codec writeCodec) {
        var registry = new CodecRegistry(writeCodec, List.of(JSON, SMILE));
        var value = Map.of(LocalDate.of(2026, 5, 16), new Sample("a", 1, false),
                           LocalDate.of(2026, 5, 17), new Sample("b", 2, true));

        byte[] frame = registry.encode(Optional.of(value), V1);

        assertThat(registry.<Map<LocalDate, Sample>>decode(frame, new TypeToken<>() {}, V1)).isEqualTo(new DecodeOutcome.Present<>(value));
    }

    @Test
    void framedBufferContainsMagicFormatIdAndVersionPrefix() {
        var jsonRegistry = new CodecRegistry(JSON, List.of(JSON));
        var smileRegistry = new CodecRegistry(SMILE, List.of(SMILE));

        byte[] jsonFrame = jsonRegistry.encode(Optional.of(new Sample("x", 0, false)), V1);
        byte[] smileFrame = smileRegistry.encode(Optional.of(new Sample("x", 0, false)), V1);

        // [MAGIC, formatId, versionHi, versionLo, ...payload]
        assertThat(jsonFrame[0]).isEqualTo(CodecRegistry.MAGIC);
        assertThat(jsonFrame[1]).isEqualTo(CodecRegistry.FMT_JSON_UTF8);
        assertThat(jsonFrame[2]).isEqualTo((byte) 0x00);
        assertThat(jsonFrame[3]).isEqualTo((byte) 0x01);
        assertThat(smileFrame[0]).isEqualTo(CodecRegistry.MAGIC);
        assertThat(smileFrame[1]).isEqualTo(CodecRegistry.FMT_SMILE);
        assertThat(smileFrame[2]).isEqualTo((byte) 0x00);
        assertThat(smileFrame[3]).isEqualTo((byte) 0x01);
    }

    @Test
    void encodeReturnsFramePlusPayloadBytes() {
        var registry = new CodecRegistry(SMILE, List.of(SMILE));
        byte[] frame = registry.encode(Optional.of(new Sample("x", 0, false)), V1);

        // Framed shape: [MAGIC, FMTID, versionHi, versionLo, ...payload]. Header is 4 bytes; payload is non-empty for any non-trivial value.
        assertThat(frame).hasSizeGreaterThan(4);
    }

    @Test
    void versionIsStoredBigEndianAndRoundTripsAtThatVersion() {
        var registry = new CodecRegistry(SMILE, List.of(SMILE));
        var value = new Sample("v", 7, true);

        byte[] frame = registry.encode(Optional.of(value), 0x0107); // 263

        assertThat(frame[2]).isEqualTo((byte) 0x01);
        assertThat(frame[3]).isEqualTo((byte) 0x07);
        assertThat(registry.decode(frame, TypeToken.of(Sample.class), 0x0107)).isEqualTo(new DecodeOutcome.Present<>(value));
    }

    @Test
    void maxVersionRoundTrips() {
        var registry = new CodecRegistry(SMILE, List.of(SMILE));
        var value = new Sample("max", 1, false);

        byte[] frame = registry.encode(Optional.of(value), 0xFFFF);

        assertThat(frame[2]).isEqualTo((byte) 0xFF);
        assertThat(frame[3]).isEqualTo((byte) 0xFF);
        assertThat(registry.decode(frame, TypeToken.of(Sample.class), 0xFFFF)).isEqualTo(new DecodeOutcome.Present<>(value));
    }

    @Test
    void versionMismatchIsNonAlarmingDiscard() {
        var registry = new CodecRegistry(SMILE, List.of(SMILE));
        byte[] frame = registry.encode(Optional.of(new Sample("x", 1, false)), 1);

        DecodeOutcome<Sample> outcome = registry.decode(frame, TypeToken.of(Sample.class), 2);

        assertThat(outcome).isInstanceOf(DecodeOutcome.Discard.class);
        var discard = (DecodeOutcome.Discard<Sample>) outcome;
        assertThat(discard.alarming()).isFalse();
        assertThat(discard.reason()).contains("schema version mismatch").contains("stored=1").contains("expected=2");
    }

    @Test
    void readerDispatchesByPerRowFormatId() {
        // The write codec is Smile, but a JSON-prefixed row coming back from a read still decodes — proves the registry's incremental-flip property.
        var registry = new CodecRegistry(SMILE, List.of(JSON, SMILE));
        var value = new Sample("mixed", 7, false);

        byte[] jsonRow = new CodecRegistry(JSON, List.of(JSON)).encode(Optional.of(value), V1);

        assertThat(registry.decode(jsonRow, TypeToken.of(Sample.class), V1)).isEqualTo(new DecodeOutcome.Present<>(value));
    }

    @Test
    void encodingEmptyProducesTombstoneFrameWithNoPayloadRegardlessOfVersion() {
        var registry = new CodecRegistry(SMILE, List.of(SMILE));

        // The tombstone is version-exempt — any version argument yields the same 2-byte marker.
        assertThat(registry.encode(Optional.empty(), 1)).containsExactly(CodecRegistry.MAGIC, CodecRegistry.FMT_TOMBSTONE);
        assertThat(registry.encode(Optional.empty(), 0xFFFF)).containsExactly(CodecRegistry.MAGIC, CodecRegistry.FMT_TOMBSTONE);
    }

    @Test
    void encodingEmptyReturnsTheSharedTombstoneConstant() {
        // The tombstone frame is invariant, so it is allocated once and shared — every empty encode returns the very same array, not a fresh copy.
        var registry = new CodecRegistry(SMILE, List.of(SMILE));

        byte[] first = registry.encode(Optional.empty(), 1);
        byte[] second = registry.encode(Optional.empty(), 2);

        assertThat(first).isSameAs(second);
    }

    @Test
    void tombstoneFrameDecodesToEmptyWhileValueFrameDecodesToPresent() {
        var registry = new CodecRegistry(SMILE, List.of(SMILE));
        var value = new Sample("x", 1, false);

        assertThat(registry.decode(registry.encode(Optional.empty(), V1), TypeToken.of(Sample.class), V1)).isInstanceOf(DecodeOutcome.Empty.class);
        assertThat(registry.decode(registry.encode(Optional.of(value), V1), TypeToken.of(Sample.class), V1)).isEqualTo(new DecodeOutcome.Present<>(value));
    }

    @Test
    void smileIsMeaningfullySmallerThanJsonForRepeatedFieldNames() {
        // Sanity check on the structural compression benefit that justified picking Smile.
        var jsonRegistry = new CodecRegistry(JSON, List.of(JSON));
        var smileRegistry = new CodecRegistry(SMILE, List.of(SMILE));
        // 50 records with the same field-name set — Smile's shared-string back-references compress these.
        List<Sample> manyRows = Stream.iterate(0, i -> i + 1).limit(50).map(i -> new Sample("row-" + i, i, i % 2 == 0)).toList();

        int jsonSize = jsonRegistry.encode(Optional.of(manyRows), V1).length;
        int smileSize = smileRegistry.encode(Optional.of(manyRows), V1).length;

        assertThat(smileSize).isLessThan(jsonSize);
    }

    @Test
    void wrongMagicIsAlarmingDiscard() {
        var registry = new CodecRegistry(SMILE, List.of(JSON, SMILE));
        // Right length for a value frame but the wrong magic — flagged as corruption, not mistaken for a tombstone.
        byte[] corruptFrame = {(byte) 0xFF, CodecRegistry.FMT_SMILE, 0x00, 0x01};

        var discard = decodeDiscard(registry, corruptFrame);
        assertThat(discard.alarming()).isTrue();
        assertThat(discard.reason()).contains("missing magic byte");
    }

    @Test
    void unknownFormatIdIsAlarmingDiscard() {
        var registry = new CodecRegistry(SMILE, List.of(JSON, SMILE));
        // Right magic but a format id that is neither a known codec nor the tombstone marker.
        byte[] unknownFormatFrame = {CodecRegistry.MAGIC, (byte) 0x7F, 0x00, 0x01};

        var discard = decodeDiscard(registry, unknownFormatFrame);
        assertThat(discard.alarming()).isTrue();
        assertThat(discard.reason()).contains("unknown format id");
    }

    @Test
    void tooShortValueFrameIsAlarmingDiscard() {
        var registry = new CodecRegistry(SMILE, List.of(SMILE));

        // Frames too short to be a value frame (≥4 bytes) and not a tombstone (exactly [MAGIC, FMT_TOMBSTONE]) — all alarming discards:
        // empty; 3-byte (right magic + format but missing the second version byte); 2-byte right-magic-wrong-second-byte; 2-byte wrong-magic.
        assertThat(decodeDiscard(registry, new byte[0]).reason()).contains("too short");
        assertThat(decodeDiscard(registry, new byte[]{CodecRegistry.MAGIC, CodecRegistry.FMT_SMILE, 0x00}).reason()).contains("too short");
        assertThat(decodeDiscard(registry, new byte[]{CodecRegistry.MAGIC, CodecRegistry.FMT_SMILE, 0x00}).alarming()).isTrue();
        assertThat(decodeDiscard(registry, new byte[]{CodecRegistry.MAGIC, CodecRegistry.FMT_SMILE}).alarming()).isTrue();
        assertThat(decodeDiscard(registry, new byte[]{(byte) 0xFF, CodecRegistry.FMT_TOMBSTONE}).alarming()).isTrue();
    }

    @Test
    void payloadThatFailsToDecodeIsAlarmingDiscard() {
        var registry = new CodecRegistry(SMILE, List.of(SMILE));
        // A validly-framed String payload decoded as a Sample record fails inside the codec — caught and surfaced as an alarming discard, never thrown.
        byte[] stringFrame = registry.encode(Optional.of("not a sample"), V1);

        DecodeOutcome<Sample> outcome = registry.decode(stringFrame, TypeToken.of(Sample.class), V1);

        assertThat(outcome).isInstanceOf(DecodeOutcome.Discard.class);
        var discard = (DecodeOutcome.Discard<Sample>) outcome;
        assertThat(discard.alarming()).isTrue();
        assertThat(discard.reason()).contains("failed to decode");
        // The reason carries the codec exception's class name, never the payload bytes.
        assertThat(discard.reason()).doesNotContain("not a sample");
    }

    @Test
    void rejectsNullFrame() {
        var registry = new CodecRegistry(SMILE, List.of(SMILE));

        assertThatThrownBy(() -> registry.decode(null, TypeToken.of(Sample.class), V1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void registryRejectsDuplicateFormatIds() {
        var anotherJson = new JsonUtf8Codec();

        assertThatThrownBy(() -> new CodecRegistry(JSON, List.of(JSON, anotherJson)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registryRejectsWriteCodecNotInReadSet() {
        assertThatThrownBy(() -> new CodecRegistry(SMILE, List.of(JSON)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must also be present in readCodecs");
    }

    @Test
    void repeatedEncodeReturnsIndependentArrays() {
        // Per-row durability: a returned `byte[]` must remain stable across subsequent encode cycles, so batched callers can retain it. The reusable internal
        // buffer is reset between calls, but each encode hands back a fresh `byte[]` snapshot.
        var registry = new CodecRegistry(SMILE, List.of(SMILE));
        byte[] first = registry.encode(Optional.of(new Sample("x", 0, false)), V1);
        byte[] firstCopy = first.clone();
        byte[] second = registry.encode(Optional.of(new Sample("y", 1, true)), V1);

        assertThat(first).isNotSameAs(second);
        // The first encode's bytes must NOT have been clobbered by the second encode.
        assertThat(first).isEqualTo(firstCopy);
    }

    @Test
    void repeatedEncodeProducesCorrectIndependentResultsWhenConsumedBeforeNextCall() {
        var registry = new CodecRegistry(SMILE, List.of(SMILE));
        var v1 = new Sample("one", 1, false);
        var v2 = new Sample("two", 2, true);
        var v3 = new Sample("three", 3, false);

        byte[] b1 = registry.encode(Optional.of(v1), V1);
        DecodeOutcome<Sample> back1 = registry.decode(b1, TypeToken.of(Sample.class), V1);

        byte[] b2 = registry.encode(Optional.of(v2), V1);
        DecodeOutcome<Sample> back2 = registry.decode(b2, TypeToken.of(Sample.class), V1);

        byte[] b3 = registry.encode(Optional.of(v3), V1);
        DecodeOutcome<Sample> back3 = registry.decode(b3, TypeToken.of(Sample.class), V1);

        assertThat(back1).isEqualTo(new DecodeOutcome.Present<>(v1));
        assertThat(back2).isEqualTo(new DecodeOutcome.Present<>(v2));
        assertThat(back3).isEqualTo(new DecodeOutcome.Present<>(v3));
    }

    @Test
    void encodeBufferGrowsForLargerPayloadsAndKeepsRoundTripping() {
        // After a small encode, encode a payload that's much larger than the default initial capacity. The reusable internal buffer must grow without
        // corrupting output, and subsequent small encodes must still round-trip correctly.
        var registry = new CodecRegistry(SMILE, List.of(SMILE));
        var smallValue = new Sample("s", 0, false);
        var bigValue = new Sample("x".repeat(4096), 99, true); // forces growth past the 256-byte default

        registry.encode(Optional.of(smallValue), V1);
        byte[] bigFrame = registry.encode(Optional.of(bigValue), V1);
        byte[] smallFrameAfterGrowth = registry.encode(Optional.of(smallValue), V1);

        assertThat(registry.decode(bigFrame, TypeToken.of(Sample.class), V1)).isEqualTo(new DecodeOutcome.Present<>(bigValue));
        assertThat(registry.decode(smallFrameAfterGrowth, TypeToken.of(Sample.class), V1)).isEqualTo(new DecodeOutcome.Present<>(smallValue));
    }

    @Test
    void formatIdConstantsAreContiguousFromZero() {
        // Spec sanity: the format-id space starts at 0x00 and the currently-assigned ids are 0x00 / 0x01 / 0x02. Any drift here is a real change to the
        // on-wire contract and must be deliberate (and matched by the plan / schema docs).
        assertThat(CodecRegistry.FMT_JSON_UTF8).isEqualTo((byte) 0x00);
        assertThat(CodecRegistry.FMT_SMILE).isEqualTo((byte) 0x01);
        assertThat(CodecRegistry.FMT_TOMBSTONE).isEqualTo((byte) 0x02);
        assertThat(CodecRegistry.MAGIC).isEqualTo((byte) 0x4A);
    }

    private static DecodeOutcome.Discard<Sample> decodeDiscard(CodecRegistry registry, byte[] frame) {
        DecodeOutcome<Sample> outcome = registry.decode(frame, TypeToken.of(Sample.class), V1);
        assertThat(outcome).isInstanceOf(DecodeOutcome.Discard.class);
        return (DecodeOutcome.Discard<Sample>) outcome;
    }

    static Stream<Codec> eachCodec() {
        return Stream.of(JSON, SMILE);
    }

    record Sample(String name, int amount, boolean flag) {}

    record TemporalSample(Optional<String> present, Optional<String> empty, Instant instant, LocalDate localDate) {}

    record CollectionSample(ImmutableList<String> list, ImmutableMap<String, Integer> map) {}
}
