package kz.zakaz.location;
import java.math.BigDecimal;
import java.util.Objects;

/** WGS84 coordinates, private by default. Reuse in seller location and pickup DTOs. */
public record GeoPoint(BigDecimal latitude, BigDecimal longitude) {
 public GeoPoint {
  Objects.requireNonNull(latitude, "latitude");
  Objects.requireNonNull(longitude, "longitude");
  if(latitude.compareTo(BigDecimal.valueOf(-90))<0 || latitude.compareTo(BigDecimal.valueOf(90))>0
    || longitude.compareTo(BigDecimal.valueOf(-180))<0 || longitude.compareTo(BigDecimal.valueOf(180))>0)
   throw new IllegalArgumentException("Invalid WGS84 coordinates");
 }
}