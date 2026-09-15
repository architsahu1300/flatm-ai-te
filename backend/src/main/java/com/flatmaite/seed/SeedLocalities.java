package com.flatmaite.seed;

import com.flatmaite.listing.Locality;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The Mumbai gazetteer the seed writes and the offline intent eval reads. Ids are UUIDv3 over
 * a stable key so re-seeding upserts the same rows and the eval resolves the same ids.
 */
public final class SeedLocalities {

  /** rentBand = typical private-room rent midpoint (₹/month). */
  public record Seed(String name, double lat, double lng, String[] aliases, int rentBand) {}

  public static final List<Seed> ALL =
      List.of(
          new Seed("Andheri East", 19.1136, 72.8697, new String[] {"andheri east", "andheri"}, 22000),
          new Seed("Andheri West", 19.1364, 72.8296, new String[] {"andheri west", "andheri"}, 25000),
          new Seed("Bandra", 19.0596, 72.8295, new String[] {"bandra west", "bandra east"}, 32000),
          new Seed("Powai", 19.1176, 72.9060, new String[] {"hiranandani", "hiranandani gardens", "iit bombay"}, 24000),
          new Seed("Lower Parel", 18.9962, 72.8330, new String[] {"lower parel", "lp"}, 33000),
          new Seed("Parel", 19.0090, 72.8400, new String[] {"parel"}, 30000),
          new Seed("Worli", 19.0176, 72.8172, new String[] {}, 38000),
          new Seed("Goregaon", 19.1663, 72.8526, new String[] {"goregaon east", "goregaon west", "film city"}, 16000),
          new Seed("Malad", 19.1874, 72.8484, new String[] {"malad west", "malad east", "mindspace"}, 14000),
          new Seed("BKC", 19.0653, 72.8693, new String[] {"bandra kurla complex", "bandra-kurla", "bandra kurla", "bkc road"}, 36000),
          new Seed("Kurla", 19.0726, 72.8845, new String[] {"kurla west", "kurla east"}, 13000),
          new Seed("Ghatkopar", 19.0790, 72.9080, new String[] {"ghatkopar east", "ghatkopar west"}, 15000),
          new Seed("Marol", 19.1197, 72.8823, new String[] {"marol naka", "mahakali"}, 20000),
          new Seed("Chakala", 19.1100, 72.8630, new String[] {"jb nagar", "j b nagar"}, 21000),
          new Seed("Sakinaka", 19.1050, 72.8880, new String[] {"saki naka"}, 16000),
          new Seed("Jogeshwari", 19.1360, 72.8490, new String[] {"jogeshwari east", "jogeshwari west"}, 17000),
          new Seed("Ram Mandir", 19.1480, 72.8450, new String[] {"ram mandir road"}, 16000),
          new Seed("Vile Parle", 19.0996, 72.8440, new String[] {"vile parle east", "vile parle west", "parle"}, 26000),
          new Seed("Santacruz", 19.0817, 72.8414, new String[] {"santa cruz", "santacruz east", "santacruz west"}, 28000),
          new Seed("Khar", 19.0700, 72.8340, new String[] {"khar west", "khar east"}, 32000),
          new Seed("Juhu", 19.1075, 72.8263, new String[] {"juhu beach"}, 34000),
          new Seed("Mahim", 19.0410, 72.8408, new String[] {}, 26000),
          new Seed("Dadar", 19.0178, 72.8478, new String[] {"dadar east", "dadar west", "shivaji park"}, 27000),
          new Seed("Matunga", 19.0270, 72.8553, new String[] {"matunga east", "matunga west"}, 26000),
          new Seed("Sion", 19.0390, 72.8619, new String[] {"sion east"}, 20000),
          new Seed("Wadala", 19.0176, 72.8562, new String[] {"wadala east"}, 22000),
          new Seed("Chembur", 19.0522, 72.9005, new String[] {"chembur east"}, 19000),
          new Seed("Vikhroli", 19.1080, 72.9280, new String[] {"vikhroli east", "vikhroli west"}, 18000),
          new Seed("Kanjurmarg", 19.1283, 72.9350, new String[] {"kanjur marg"}, 17000),
          new Seed("Bhandup", 19.1440, 72.9370, new String[] {}, 15000),
          new Seed("Mulund", 19.1726, 72.9564, new String[] {"mulund west"}, 17000),
          new Seed("Thane", 19.2183, 72.9781, new String[] {"thane west", "ghodbunder"}, 15000),
          new Seed("Kandivali", 19.2045, 72.8519, new String[] {"kandivali east", "kandivali west"}, 15000),
          new Seed("Borivali", 19.2307, 72.8567, new String[] {"borivali west", "borivali east"}, 16000),
          new Seed("Vashi", 19.0771, 72.9987, new String[] {"navi mumbai"}, 17000),
          new Seed("Airoli", 19.1590, 72.9986, new String[] {}, 15000),
          new Seed("Kharghar", 19.0330, 73.0650, new String[] {}, 13000),
          new Seed("Colaba", 18.9067, 72.8147, new String[] {"cuffe parade"}, 40000));

  private SeedLocalities() {}

  public static UUID id(String name) {
    return UUID.nameUUIDFromBytes(("flatmaite:locality:" + name).getBytes(StandardCharsets.UTF_8));
  }

  public static List<Locality> entities() {
    List<Locality> out = new ArrayList<>(ALL.size());
    for (Seed s : ALL) {
      Locality l =
          Locality.builder()
              .name(s.name())
              .lat(s.lat())
              .lng(s.lng())
              .aliases(Arrays.copyOf(s.aliases(), s.aliases().length))
              .build();
      l.setId(id(s.name()));
      out.add(l);
    }
    return out;
  }
}
