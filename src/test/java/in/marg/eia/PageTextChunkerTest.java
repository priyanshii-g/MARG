package in.marg.eia;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PageTextChunkerTest {

    @Test
    void preservesPageAndChunkOffsetsAndAppliesConfiguredOverlap() {
        var chunker = new PageTextChunker();

        var chunks = chunker.chunkPage(4, "a".repeat(2500), 1200, 200, 7);

        assertEquals(3, chunks.size());
        assertEquals(4, chunks.get(0).pageNumber());
        assertEquals(7, chunks.get(0).chunkIndex());
        assertEquals(8, chunks.get(1).chunkIndex());
        assertEquals(0, chunks.get(0).startChar());
        assertEquals(1200, chunks.get(0).endChar());
        assertEquals(1000, chunks.get(1).startChar());
        assertEquals(2200, chunks.get(1).endChar());
    }
}
