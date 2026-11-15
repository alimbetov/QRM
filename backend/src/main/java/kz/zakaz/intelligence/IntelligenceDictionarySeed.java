package kz.zakaz.intelligence;

import java.math.BigDecimal;
import java.util.*;

/**
 * Governed S1 reference vocabulary for smoke only. These are project-owned symbolic codes,
 * NOT official KATO, GS1, UNSPSC codes nor full external standards. Persistent numeric keys
 * and full versioned translations belong to the future PostgreSQL UniversalDictionary.
 */
public final class IntelligenceDictionarySeed {
    private IntelligenceDictionarySeed() {}

    public enum Type { ENUM, DECIMAL, BOOLEAN }
    public record Definition(String code, Type type, String unit, Set<String> allowedClasses,
                             Set<String> allowedValues) {
        public Definition {
            Objects.requireNonNull(code); Objects.requireNonNull(type);
            allowedClasses = Set.copyOf(allowedClasses);
            allowedValues = Set.copyOf(allowedValues);
            if (allowedClasses.isEmpty() || (type != Type.ENUM && !allowedValues.isEmpty()))
                throw new IllegalArgumentException("Invalid definition");
        }
    }
    public static final Set<String> CLASSES = Set.of(
        "PHONE","LAPTOP","TABLET","TELEVISION","WASHER","REFRIGERATOR",
        "CLOTHING","FOOTWEAR","FURNITURE","BUILDING_MATERIAL","ELECTRICAL_COMPONENT",
        "AUTO_PART","FOOD","COSMETICS","MEDICAL_ITEM","BOOK","TOY",
        "ELECTRICIAN_SERVICE","PLUMBING_SERVICE","TUTOR_SERVICE","CLEANING_SERVICE",
        "EQUIPMENT_REPAIR","DIGITAL_COURSE","SOFTWARE_LICENSE"
    );
    public static final Set<String> LANGUAGES = Set.of("kk","ru","en","zh","tr");
    public static final Set<String> PILOT_CITIES = Set.of(
        "ALMATY","ASTANA","SHYMKENT","KARAGANDA","AKTOBE","ATYRAU","AKTAU",
        "PAVLODAR","KOSTANAY","TARAZ");
    private static final Map<String, Definition> DEFINITIONS = build();

    private static Map<String, Definition> build() {
        Map<String, Definition> m = new LinkedHashMap<>();
        Set<String> electronics=Set.of("PHONE","LAPTOP","TABLET","TELEVISION");
        Set<String> apparel=Set.of("CLOTHING","FOOTWEAR");
        Set<String> services=Set.of("ELECTRICIAN_SERVICE","PLUMBING_SERVICE","TUTOR_SERVICE",
            "CLEANING_SERVICE","EQUIPMENT_REPAIR");
        enumDef(m,"COLOR",CLASSES,"BLACK WHITE GREY BLUE RED GREEN BEIGE BROWN YELLOW PINK GOLD SILVER PURPLE ORANGE MULTICOLOR");
        enumDef(m,"MATERIAL",Set.of("CLOTHING","FOOTWEAR","FURNITURE","BUILDING_MATERIAL","ELECTRICAL_COMPONENT","AUTO_PART"),
            "COTTON WOOL LINEN POLYESTER LEATHER ECO_LEATHER WOOD MDF STEEL ALUMINUM COPPER PLASTIC GLASS CERAMIC CONCRETE RUBBER SILICONE");
        enumDef(m,"BRAND",CLASSES,"SAMSUNG APPLE XIAOMI LENOVO LG BOSCH BEKO NIKE ADIDAS GENERIC OTHER");
        enumDef(m,"CONDITION",CLASSES,"NEW USED REFURBISHED");
        enumDef(m,"SIZE_SYSTEM",apparel,"INT_APPAREL EU_APPAREL EU_SHOES UK_SHOES US_SHOES");
        enumDef(m,"APPAREL_SIZE",Set.of("CLOTHING"),"XS S M L XL XXL XXXL");
        enumDef(m,"FOOTWEAR_SIZE",Set.of("FOOTWEAR"),"EU_36 EU_37 EU_38 EU_39 EU_40 EU_41 EU_42 EU_43 EU_44 EU_45");
        enumDef(m,"SERVICE_LANGUAGE",services,"kk ru en zh tr");
        enumDef(m,"COURSE_LANGUAGE",Set.of("DIGITAL_COURSE"),"kk ru en zh tr");
        enumDef(m,"DIGITAL_FORMAT",Set.of("DIGITAL_COURSE"),"VIDEO PDF INTERACTIVE VR");
        enumDef(m,"CONNECTOR_TYPE",Set.of("ELECTRICAL_COMPONENT","PHONE","LAPTOP","TABLET"),"USB_C USB_A LIGHTNING EU_PLUG TYPE_C_HDMI OTHER");
        decimal(m,"STORAGE_GB","GB",Set.of("PHONE","LAPTOP","TABLET"));
        decimal(m,"RAM_GB","GB",electronics);
        decimal(m,"DISPLAY_INCH","INCH",electronics);
        decimal(m,"WEIGHT_KG","KG",CLASSES);
        decimal(m,"LENGTH_CM","CM",CLASSES);
        decimal(m,"WIDTH_CM","CM",CLASSES);
        decimal(m,"HEIGHT_CM","CM",CLASSES);
        decimal(m,"POWER_W","W",Set.of("TELEVISION","WASHER","REFRIGERATOR","ELECTRICAL_COMPONENT"));
        decimal(m,"CAPACITY_KG","KG",Set.of("WASHER"));
        decimal(m,"VOLUME_L","L",Set.of("REFRIGERATOR","FOOD","COSMETICS"));
        decimal(m,"SERVICE_DURATION_MIN","MIN",services);
        decimal(m,"SERVICE_AREA_M2","M2",Set.of("ELECTRICIAN_SERVICE","PLUMBING_SERVICE","CLEANING_SERVICE"));
        decimal(m,"WARRANTY_MONTHS","MONTH",CLASSES);
        return Map.copyOf(m);
    }
    private static void enumDef(Map<String,Definition> m,String name,Set<String> categories,String values) {
        m.put(name,new Definition(name,Type.ENUM,null,categories,Set.of(values.split(" "))));
    }
    private static void decimal(Map<String,Definition> m,String name,String unit,Set<String> categories) {
        m.put(name,new Definition(name,Type.DECIMAL,unit,categories,Set.of()));
    }
    public static Map<String, Definition> definitions() { return DEFINITIONS; }

    /** Validate a canonical smoke fact. Type/system dependencies need dedicated S2 rules. */
    public static void validate(String classification, String attribute, String value) {
        if (!CLASSES.contains(classification)) throw new IllegalArgumentException("Unknown class");
        Definition d = DEFINITIONS.get(attribute);
        if (d == null || !d.allowedClasses().contains(classification))
            throw new IllegalArgumentException("Attribute not applicable");
        Objects.requireNonNull(value);
        switch (d.type()) {
            case ENUM -> {
                if (!d.allowedValues().contains(value)) throw new IllegalArgumentException("Unknown enum");
            }
            case DECIMAL -> {
                BigDecimal parsed = new BigDecimal(value);
                if (parsed.signum() < 0) throw new IllegalArgumentException("Negative measurement");
            }
            case BOOLEAN -> {
                if (!"true".equals(value) && !"false".equals(value))
                    throw new IllegalArgumentException("Invalid boolean");
            }
        }
    }
    public static String canonicalNumeric(String classCode,String attribute,String value) {
        validate(classCode,attribute,value);
        Definition d = DEFINITIONS.get(attribute);
        return d.type() == Type.DECIMAL ? new BigDecimal(value).stripTrailingZeros().toPlainString() : value;
    }
}
