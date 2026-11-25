package in.marg.eia;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Shared page-local character chunking with the MARG proposal chunker's natural-break and overlap rules. */
@Component
public class PageTextChunker {

    public static final int DEFAULT_CHUNK_SIZE = 1200;
    public static final int DEFAULT_OVERLAP = 200;

    public List<PageChunk> chunkPage(int pageNumber, String text, int chunkSize, int overlap, int firstChunkIndex) {
        if (pageNumber < 1) throw new IllegalArgumentException("pageNumber must be positive.");
        if (text == null || text.isBlank()) return List.of();
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be positive.");
        if (overlap < 0 || overlap >= chunkSize) throw new IllegalArgumentException("overlap must be >= 0 and < chunkSize.");
        if (firstChunkIndex < 0) throw new IllegalArgumentException("firstChunkIndex cannot be negative.");

        List<PageChunk> chunks = new ArrayList<>();
        int start = 0;
        int chunkIndex = firstChunkIndex;
        while (start < text.length()) {
            int target = Math.min(start + chunkSize, text.length());
            int end = naturalBreak(text, start, target);
            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isBlank()) {
                chunks.add(new PageChunk(pageNumber, chunkIndex++, start, end, chunkText));
            }
            if (end >= text.length()) break;
            int nextStart = Math.max(start + 1, end - overlap);
            start = nextStart <= start ? end : nextStart;
        }
        return List.copyOf(chunks);
    }

    public List<PageChunk> chunkPage(int pageNumber, String text) {
        return chunkPage(pageNumber, text, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP, 0);
    }

    private static int naturalBreak(String text, int start, int target) {
        if (target >= text.length()) return text.length();
        int minimumBreak = start + Math.max(100, (target - start) / 2);
        for (int index = target; index > minimumBreak; index--) {
            char current = text.charAt(index - 1);
            if (Character.isWhitespace(current) || current == '.' || current == ':' || current == ';' || current == ',') {
                return index;
            }
        }
        return target;
    }

    public record PageChunk(int pageNumber, int chunkIndex, int startChar, int endChar, String text) {}
}
