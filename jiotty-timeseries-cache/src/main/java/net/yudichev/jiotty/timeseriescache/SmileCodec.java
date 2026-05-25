package net.yudichev.jiotty.timeseriescache;

import com.fasterxml.jackson.dataformat.smile.SmileFactory;

/// Smile codec — `formatId = 0x01`, the production default write codec. Jackson's own binary format. Self-describing; type-preserving; significantly more
/// compact than JSON for typed POJOs/records because field names repeated across rows compress to single-byte back-references after first occurrence within a
/// parser/generator scope.
final class SmileCodec extends AbstractJacksonCodec {
    public SmileCodec() {
        super(new SmileFactory());
    }

    @Override
    public byte formatId() {
        return CodecRegistry.FMT_SMILE;
    }
}
