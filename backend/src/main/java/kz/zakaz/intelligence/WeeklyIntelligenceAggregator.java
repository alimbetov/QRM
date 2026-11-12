package kz.zakaz.intelligence;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pure Java weekly aggregate smoke foundation, independent from JPA domain entities.
 * Production events must be permissioned, de-duplicated, and mapped to canonical SKUs.
 * Weeks follow ISO Monday starts in the explicitly chosen business time zone.
 */
public final class WeeklyIntelligenceAggregator {
    private WeeklyIntelligenceAggregator() {}

    public record Day(UUID sellerId, UUID skuId, String cityCode, LocalDate date,
                      long soldUnits, long demandUnits, long impressions, Boolean inStock) {
        public Day {
            Objects.requireNonNull(sellerId);
            Objects.requireNonNull(skuId);
            Objects.requireNonNull(date);
            if (cityCode == null || cityCode.isBlank()) throw new IllegalArgumentException("City required");
            if (soldUnits < 0 || demandUnits < 0 || impressions < 0)
                throw new IllegalArgumentException("Negative observation");
        }
    }

    public record Key(UUID sellerId, UUID skuId, String cityCode) {}
    public record WeekKey(Key key, LocalDate monday) {}

    public record Week(WeekKey id, long soldUnits, long demandUnits, long impressions,
                       int availableDays, int stockoutDays, int unknownStockDays, int observedDays) {}

    public record Window(Key key, LocalDate lastClosedMonday, int requestedWeeks,
                         int observedWeeks, long soldUnits, long demandUnits, long impressions,
                         int availableDays, int stockoutDays, int unknownStockDays) {
        /** Never divide by calendar days when days of stock exposure are missing. */
        public OptionalDouble soldPerAvailableDay() {
            return availableDays == 0 ? OptionalDouble.empty()
                    : OptionalDouble.of((double) soldUnits / availableDays);
        }
        public boolean complete() { return observedWeeks == requestedWeeks && unknownStockDays == 0; }
    }

    public static LocalDate monday(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static List<Week> aggregateClosedWeeks(Collection<Day> input, Instant asOf, ZoneId zone) {
        Objects.requireNonNull(input); Objects.requireNonNull(asOf); Objects.requireNonNull(zone);
        Set<String> unique = new HashSet<>();
        Map<WeekKey, List<Day>> buckets = new HashMap<>();
        for (Day day : input) {
            if (day == null) throw new IllegalArgumentException("Null daily input");
            // This smoke contract accepts one already-deduplicated daily row per seller/SKU/city.
            String identity = day.sellerId() + "/" + day.skuId() + "/" + day.cityCode() + "/" + day.date();
            if (!unique.add(identity)) throw new IllegalArgumentException("Duplicate daily observation " + identity);
            LocalDate start = monday(day.date());
            Instant end = start.plusDays(7).atStartOfDay(zone).toInstant();
            if (end.isAfter(asOf)) continue; // no partial week features
            WeekKey key = new WeekKey(new Key(day.sellerId(), day.skuId(), day.cityCode()), start);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(day);
        }
        return buckets.entrySet().stream().map(e -> {
            List<Day> days = e.getValue();
            return new Week(e.getKey(),
                days.stream().mapToLong(Day::soldUnits).sum(),
                days.stream().mapToLong(Day::demandUnits).sum(),
                days.stream().mapToLong(Day::impressions).sum(),
                (int) days.stream().filter(d -> Boolean.TRUE.equals(d.inStock())).count(),
                (int) days.stream().filter(d -> Boolean.FALSE.equals(d.inStock())).count(),
                (int) days.stream().filter(d -> d.inStock() == null).count(),
                days.size());
        }).sorted(Comparator.comparing((Week w) -> w.id().monday())
                    .thenComparing(w -> w.id().key().sellerId())
                    .thenComparing(w -> w.id().key().skuId()))
          .toList();
    }

    public static Window rollingWindow(Collection<Week> weeks, Key key, Instant asOf, ZoneId zone, int size) {
        Objects.requireNonNull(weeks); Objects.requireNonNull(key); Objects.requireNonNull(asOf);
        Objects.requireNonNull(zone);
        if (size <= 0 || size > 52) throw new IllegalArgumentException("Invalid week count");
        LocalDate last = monday(asOf.atZone(zone).toLocalDate()).minusWeeks(1);
        // On a Monday at/after local midnight the previous week is closed.
        LocalDate earliest = last.minusWeeks(size - 1L);
        List<Week> selected = weeks.stream()
            .filter(w -> w.id().key().equals(key) && !w.id().monday().isBefore(earliest)
                    && !w.id().monday().isAfter(last))
            .toList();
        if (selected.stream().map(w -> w.id().monday()).distinct().count() != selected.size())
            throw new IllegalArgumentException("Duplicate weekly observation");
        return new Window(key, last, size, selected.size(),
            selected.stream().mapToLong(Week::soldUnits).sum(),
            selected.stream().mapToLong(Week::demandUnits).sum(),
            selected.stream().mapToLong(Week::impressions).sum(),
            selected.stream().mapToInt(Week::availableDays).sum(),
            selected.stream().mapToInt(Week::stockoutDays).sum(),
            selected.stream().mapToInt(Week::unknownStockDays).sum());
    }
}
