package net.yudichev.jiotty.analyticscache;

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

    @ParameterizedTest
    @MethodSource("eachCodec")
    void roundTripPreservesSimplePojo(Codec writeCodec) {
        var registry = new CodecRegistry(writeCodec, List.of(JSON, SMILE));
        var value = new Sample("hello", 42, true);

        byte[] frame = registry.encode(value);
        Sample back = registry.decode(frame, TypeToken.of(Sample.class));

        assertThat(back).isEqualTo(value);
    }

    @ParameterizedTest
    @MethodSource("eachCodec")
    void roundTripPreservesJdk8AndJavaTime(Codec writeCodec) {
        var registry = new CodecRegistry(writeCodec, List.of(JSON, SMILE));
        var value = new TemporalSample(Optional.of("present"), Optional.empty(), Instant.parse("2026-05-16T12:34:56Z"), LocalDate.of(2026, 5, 16));

        byte[] frame = registry.encode(value);
        TemporalSample back = registry.decode(frame, TypeToken.of(TemporalSample.class));

        assertThat(back).isEqualTo(value);
    }

    @ParameterizedTest
    @MethodSource("eachCodec")
    void roundTripPreservesGuavaImmutableCollections(Codec writeCodec) {
        var registry = new CodecRegistry(writeCodec, List.of(JSON, SMILE));
        var value = new CollectionSample(ImmutableList.of("a", "b", "c"), ImmutableMap.of("k1", 1, "k2", 2));

        byte[] frame = registry.encode(value);
        CollectionSample back = registry.decode(frame, TypeToken.of(CollectionSample.class));

        assertThat(back).isEqualTo(value);
    }

    @ParameterizedTest
    @MethodSource("eachCodec")
    void roundTripPreservesGenericTypeViaTypeToken(Codec writeCodec) {
        var registry = new CodecRegistry(writeCodec, List.of(JSON, SMILE));
        var value = Map.of(LocalDate.of(2026, 5, 16), new Sample("a", 1, false),
                           LocalDate.of(2026, 5, 17), new Sample("b", 2, true));

        byte[] frame = registry.encode(value);
        Map<LocalDate, Sample> back = registry.decode(frame, new TypeToken<>() {});

        assertThat(back).isEqualTo(value);
    }

    @Test
    void framedBufferContainsMagicAndFormatIdPrefix() {
        var jsonRegistry = new CodecRegistry(JSON, List.of(JSON));
        var smileRegistry = new CodecRegistry(SMILE, List.of(SMILE));

        byte[] jsonFrame = jsonRegistry.encode(new Sample("x", 0, false));
        byte[] smileFrame = smileRegistry.encode(new Sample("x", 0, false));

        assertThat(jsonFrame[0]).isEqualTo(CodecRegistry.MAGIC);
        assertThat(jsonFrame[1]).isEqualTo(CodecRegistry.FMT_JSON_UTF8);
        assertThat(smileFrame[0]).isEqualTo(CodecRegistry.MAGIC);
        assertThat(smileFrame[1]).isEqualTo(CodecRegistry.FMT_SMILE);
    }

    @Test
    void encodeReturnsFramePlusPayloadBytes() {
        var registry = new CodecRegistry(SMILE, List.of(SMILE));
        byte[] frame = registry.encode(new Sample("x", 0, false));

        // Framed shape: [MAGIC, FMTID, ...payload]. Header is 2 bytes; payload is non-empty for any non-trivial value.
        assertThat(frame).hasSizeGreaterThan(2);
    }

    @Test
    void readerDispatchesByPerRowFormatId() {
        // The write codec is Smile, but a JSON-prefixed row coming back from a read still decodes — proves the registry's incremental-flip property.
        var registry = new CodecRegistry(SMILE, List.of(JSON, SMILE));
        var value = new Sample("mixed", 7, false);

        byte[] jsonRow = new CodecRegistry(JSON, List.of(JSON)).encode(value);
        Sample back = registry.decode(jsonRow, TypeToken.of(Sample.class));

        assertThat(back).isEqualTo(value);
    }

    @Test
    void smileIsMeaningfullySmallerThanJsonForRepeatedFieldNames() {
        // Sanity check on the structural compression benefit that justified picking Smile.
        var jsonRegistry = new CodecRegistry(JSON, List.of(JSON));
        var smileRegistry = new CodecRegistry(SMILE, List.of(SMILE));
        // 50 records with the same field-name set — Smile's shared-string back-references compress these.
        List<Sample> manyRows = Stream.iterate(0, i -> i + 1).limit(50).map(i -> new Sample("row-" + i, i, i % 2 == 0)).toList();

        int jsonSize = jsonRegistry.encode(manyRows).length;
        int smileSize = smileRegistry.encode(manyRows).length;

        assertThat(smileSize).isLessThan(jsonSize);
    }

    @Test
    void rejectsFrameWithWrongMagic() {
        var registry = new CodecRegistry(SMILE, List.of(JSON, SMILE));
        byte[] corruptFrame = {(byte) 0xFF, CodecRegistry.FMT_SMILE, 0x00, 0x00};

        assertThatThrownBy(() -> registry.decode(corruptFrame, TypeToken.of(Sample.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing magic byte");
    }

    @Test
    void rejectsFrameWithUnknownFormatId() {
        var registry = new CodecRegistry(SMILE, List.of(JSON, SMILE));
        byte[] unknownFormatFrame = {CodecRegistry.MAGIC, (byte) 0x7F, 0x00, 0x00};

        assertThatThrownBy(() -> registry.decode(unknownFormatFrame, TypeToken.of(Sample.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown format id");
    }

    @Test
    void rejectsTooShortFrame() {
        var registry = new CodecRegistry(SMILE, List.of(SMILE));

        assertThatThrownBy(() -> registry.decode(new byte[0], TypeToken.of(Sample.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
        assertThatThrownBy(() -> registry.decode(new byte[]{CodecRegistry.MAGIC}, TypeToken.of(Sample.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void rejectsNullFrame() {
        var registry = new CodecRegistry(SMILE, List.of(SMILE));

        assertThatThrownBy(() -> registry.decode(null, TypeToken.of(Sample.class)))
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
        byte[] first = registry.encode(new Sample("x", 0, false));
        byte[] firstCopy = first.clone();
        byte[] second = registry.encode(new Sample("y", 1, true));

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

        byte[] b1 = registry.encode(v1);
        Sample back1 = registry.decode(b1, TypeToken.of(Sample.class));

        byte[] b2 = registry.encode(v2);
        Sample back2 = registry.decode(b2, TypeToken.of(Sample.class));

        byte[] b3 = registry.encode(v3);
        Sample back3 = registry.decode(b3, TypeToken.of(Sample.class));

        assertThat(back1).isEqualTo(v1);
        assertThat(back2).isEqualTo(v2);
        assertThat(back3).isEqualTo(v3);
    }

    @Test
    void encodeBufferGrowsForLargerPayloadsAndKeepsRoundTripping() {
        // After a small encode, encode a payload that's much larger than the default initial capacity. The reusable internal buffer must grow without
        // corrupting output, and subsequent small encodes must still round-trip correctly.
        var registry = new CodecRegistry(SMILE, List.of(SMILE));
        var smallValue = new Sample("s", 0, false);
        var bigValue = new Sample("x".repeat(4096), 99, true); // forces growth past the 256-byte default

        registry.encode(smallValue);
        byte[] bigFrame = registry.encode(bigValue);

        Sample backBig = registry.decode(bigFrame, TypeToken.of(Sample.class));
        byte[] smallFrameAfterGrowth = registry.encode(smallValue);
        Sample backSmall = registry.decode(smallFrameAfterGrowth, TypeToken.of(Sample.class));

        assertThat(backBig).isEqualTo(bigValue);
        assertThat(backSmall).isEqualTo(smallValue);
    }

    @Test
    void formatIdConstantsAreContiguousFromZero() {
        // Spec sanity: the format-id space starts at 0x00 and the currently-assigned ids are 0x00 / 0x01. Any drift here is a real change to the on-wire
        // contract and must be deliberate (and matched by the plan / schema docs).
        assertThat(CodecRegistry.FMT_JSON_UTF8).isEqualTo((byte) 0x00);
        assertThat(CodecRegistry.FMT_SMILE).isEqualTo((byte) 0x01);
        assertThat(CodecRegistry.MAGIC).isEqualTo((byte) 0x4A);
    }

    static Stream<Codec> eachCodec() {
        return Stream.of(JSON, SMILE);
    }

    record Sample(String name, int amount, boolean flag) {}

    record TemporalSample(Optional<String> present, Optional<String> empty, Instant instant, LocalDate localDate) {}

    record CollectionSample(ImmutableList<String> list, ImmutableMap<String, Integer> map) {}
}
