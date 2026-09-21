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
  public record Seed(String name, String city, double lat, double lng, String[] aliases, int rentBand) {}

  public static final List<Seed> ALL =
      List.of(
          new Seed("Andheri East", "Mumbai", 19.1136, 72.8697, new String[] {"andheri east", "andheri"}, 22000),
          new Seed("Andheri West", "Mumbai", 19.1364, 72.8296, new String[] {"andheri west", "andheri"}, 25000),
          new Seed("Bandra", "Mumbai", 19.0596, 72.8295, new String[] {"bandra west", "bandra east"}, 32000),
          new Seed("Powai", "Mumbai", 19.1176, 72.9060, new String[] {"hiranandani", "hiranandani gardens", "iit bombay"}, 24000),
          new Seed("Lower Parel", "Mumbai", 18.9962, 72.8330, new String[] {"lower parel", "lp"}, 33000),
          new Seed("Parel", "Mumbai", 19.0090, 72.8400, new String[] {"parel"}, 30000),
          new Seed("Worli", "Mumbai", 19.0176, 72.8172, new String[] {}, 38000),
          new Seed("Goregaon", "Mumbai", 19.1663, 72.8526, new String[] {"goregaon east", "goregaon west", "film city"}, 16000),
          new Seed("Malad", "Mumbai", 19.1874, 72.8484, new String[] {"malad west", "malad east", "mindspace"}, 14000),
          new Seed("BKC", "Mumbai", 19.0653, 72.8693, new String[] {"bandra kurla complex", "bandra-kurla", "bandra kurla", "bkc road"}, 36000),
          new Seed("Kurla", "Mumbai", 19.0726, 72.8845, new String[] {"kurla west", "kurla east"}, 13000),
          new Seed("Ghatkopar", "Mumbai", 19.0790, 72.9080, new String[] {"ghatkopar east", "ghatkopar west"}, 15000),
          new Seed("Marol", "Mumbai", 19.1197, 72.8823, new String[] {"marol naka", "mahakali"}, 20000),
          new Seed("Chakala", "Mumbai", 19.1100, 72.8630, new String[] {"jb nagar", "j b nagar"}, 21000),
          new Seed("Sakinaka", "Mumbai", 19.1050, 72.8880, new String[] {"saki naka"}, 16000),
          new Seed("Jogeshwari", "Mumbai", 19.1360, 72.8490, new String[] {"jogeshwari east", "jogeshwari west"}, 17000),
          new Seed("Ram Mandir", "Mumbai", 19.1480, 72.8450, new String[] {"ram mandir road"}, 16000),
          new Seed("Vile Parle", "Mumbai", 19.0996, 72.8440, new String[] {"vile parle east", "vile parle west", "parle"}, 26000),
          new Seed("Santacruz", "Mumbai", 19.0817, 72.8414, new String[] {"santa cruz", "santacruz east", "santacruz west"}, 28000),
          new Seed("Khar", "Mumbai", 19.0700, 72.8340, new String[] {"khar west", "khar east"}, 32000),
          new Seed("Juhu", "Mumbai", 19.1075, 72.8263, new String[] {"juhu beach"}, 34000),
          new Seed("Mahim", "Mumbai", 19.0410, 72.8408, new String[] {}, 26000),
          new Seed("Dadar", "Mumbai", 19.0178, 72.8478, new String[] {"dadar east", "dadar west", "shivaji park"}, 27000),
          new Seed("Matunga", "Mumbai", 19.0270, 72.8553, new String[] {"matunga east", "matunga west"}, 26000),
          new Seed("Sion", "Mumbai", 19.0390, 72.8619, new String[] {"sion east"}, 20000),
          new Seed("Wadala", "Mumbai", 19.0176, 72.8562, new String[] {"wadala east"}, 22000),
          new Seed("Chembur", "Mumbai", 19.0522, 72.9005, new String[] {"chembur east"}, 19000),
          new Seed("Vikhroli", "Mumbai", 19.1080, 72.9280, new String[] {"vikhroli east", "vikhroli west"}, 18000),
          new Seed("Kanjurmarg", "Mumbai", 19.1283, 72.9350, new String[] {"kanjur marg"}, 17000),
          new Seed("Bhandup", "Mumbai", 19.1440, 72.9370, new String[] {}, 15000),
          new Seed("Mulund", "Mumbai", 19.1726, 72.9564, new String[] {"mulund west"}, 17000),
          new Seed("Thane", "Mumbai", 19.2183, 72.9781, new String[] {"thane west", "ghodbunder"}, 15000),
          new Seed("Kandivali", "Mumbai", 19.2045, 72.8519, new String[] {"kandivali east", "kandivali west"}, 15000),
          new Seed("Borivali", "Mumbai", 19.2307, 72.8567, new String[] {"borivali west", "borivali east"}, 16000),
          new Seed("Vashi", "Mumbai", 19.0771, 72.9987, new String[] {"navi mumbai"}, 17000),
          new Seed("Airoli", "Mumbai", 19.1590, 72.9986, new String[] {}, 15000),
          new Seed("Kharghar", "Mumbai", 19.0330, 73.0650, new String[] {}, 13000),
          new Seed("Colaba", "Mumbai", 18.9067, 72.8147, new String[] {"cuffe parade"}, 40000),
          new Seed("Versova", "Mumbai", 19.1290, 72.8140, new String[] {"versova beach", "seven bungalows"}, 27000),
          new Seed("Oshiwara", "Mumbai", 19.1480, 72.8320, new String[] {"lokhandwala", "lokhandwala complex"}, 24000),
          new Seed("Dahisar", "Mumbai", 19.2500, 72.8600, new String[] {"dahisar east", "dahisar west"}, 14000),
          new Seed("Mira Road", "Mumbai", 19.2810, 72.8710, new String[] {"mira bhayandar"}, 12000),
          new Seed("Bhayandar", "Mumbai", 19.3020, 72.8510, new String[] {"bhayander"}, 12000),
          new Seed("Byculla", "Mumbai", 18.9760, 72.8330, new String[] {}, 24000),
          new Seed("Prabhadevi", "Mumbai", 19.0150, 72.8280, new String[] {"elphinstone"}, 35000),
          new Seed("Vidyavihar", "Mumbai", 19.0800, 72.8970, new String[] {}, 18000),
          new Seed("Kalyan", "Mumbai", 19.2350, 73.1300, new String[] {"kalyan west", "kalyan east"}, 11000),
          new Seed("Dombivli", "Mumbai", 19.2170, 73.0870, new String[] {"dombivali"}, 11000),
          new Seed("Churchgate", "Mumbai", 18.9350, 72.8270, new String[] {}, 42000),
          new Seed("Marine Lines", "Mumbai", 18.9450, 72.8230, new String[] {"marine drive"}, 40000),
          new Seed("Fort", "Mumbai", 18.9340, 72.8360, new String[] {"ballard estate"}, 38000),
          new Seed("Grant Road", "Mumbai", 18.9630, 72.8150, new String[] {}, 30000),
          new Seed("Tardeo", "Mumbai", 18.9700, 72.8100, new String[] {}, 36000),
          new Seed("Malabar Hill", "Mumbai", 18.9550, 72.7950, new String[] {"walkeshwar"}, 45000),
          new Seed("Nerul", "Mumbai", 19.0330, 73.0180, new String[] {"nerul east", "nerul west"}, 16000),
          new Seed("Belapur", "Mumbai", 19.0170, 73.0360, new String[] {"cbd belapur"}, 15000),
          new Seed("Ghansoli", "Mumbai", 19.1200, 72.9980, new String[] {}, 14000),
          new Seed("Panvel", "Mumbai", 18.9890, 73.1100, new String[] {"new panvel"}, 12000),
          new Seed("Seawoods", "Mumbai", 19.0180, 73.0180, new String[] {"seawoods darave"}, 18000));

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
              .city(s.city())
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
