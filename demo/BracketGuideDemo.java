import java.util.List;

final class BracketGuideDemo {
    int countVisibleValues(List<String> values) {
        int count = 0;

        for (String value : values) {
            if (!value.isBlank()) {
                count += switch (value.length()) {
                    case 1 -> 1;
                    case 2, 3 -> 2;
                    default -> value.startsWith("[") ? 3 : 4;
                };
            }
        }

        int[] nested = {1, (2 + 3), values.size()};
        return count + nested[0];
    }
}
