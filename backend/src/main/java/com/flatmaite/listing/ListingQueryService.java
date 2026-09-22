package com.flatmaite.listing;

import com.flatmaite.common.domain.Furnishing;
import com.flatmaite.common.domain.ListingType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * JdbcTemplate-based filtered retrieval. Produces ordered listing ids + total; entity hydration
 * happens via JPA with an entity graph. The WHERE fragment builder is shared with the AI
 * pipeline's hard-filter stage.
 */
@Service
@RequiredArgsConstructor
public class ListingQueryService {

  public enum Sort {
    NEWEST("l.updated_at DESC"),
    PRICE_ASC("l.rent_monthly ASC"),
    PRICE_DESC("l.rent_monthly DESC");

    final String sql;

    Sort(String sql) {
      this.sql = sql;
    }
  }

  public record IdPage(List<UUID> ids, long total) {}

  private final NamedParameterJdbcTemplate jdbc;
  private final ListingRepository listings;

  public IdPage findIds(ListingFilters filters, Sort sort, int page, int size) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = buildWhere(filters, params);
    String sql =
        """
        SELECT l.id, count(*) OVER () AS total
        FROM listings l
        LEFT JOIN properties p ON p.id = l.property_id
        WHERE %s
        ORDER BY l.is_boosted DESC, %s, l.id
        LIMIT :limit OFFSET :offset
        """
            .formatted(where, sort.sql);
    params.put("limit", size);
    params.put("offset", page * size);

    List<UUID> ids = new ArrayList<>();
    long[] total = {0};
    jdbc.query(
        sql,
        params,
        rs -> {
          ids.add(rs.getObject("id", UUID.class));
          total[0] = rs.getLong("total");
        });
    return new IdPage(ids, total[0]);
  }

  /** Hydrates in the id order the query produced. */
  public List<Listing> hydrate(List<UUID> ids) {
    Map<UUID, Listing> byId = new LinkedHashMap<>();
    listings.findWithAssetsByIdIn(ids).forEach(l -> byId.put(l.getId(), l));
    List<Listing> ordered = new ArrayList<>(ids.size());
    for (UUID id : ids) {
      Listing l = byId.get(id);
      if (l != null) {
        ordered.add(l);
      }
    }
    return ordered;
  }

  /**
   * Builds the WHERE fragment for active-listing retrieval. Mutates {@code params}. Shared with the
   * AI pipeline (which adds vector and full-text ordering on top).
   */
  public static String buildWhere(ListingFilters f, Map<String, Object> params) {
    StringBuilder where =
        new StringBuilder("l.deleted_at IS NULL AND l.status = 'ACTIVE'::listing_status");

    if (f.localityIds() != null && !f.localityIds().isEmpty()) {
      where.append(" AND p.locality_id IN (:localityIds)");
      params.put("localityIds", f.localityIds());
    }
    if (f.excludeLocalityIds() != null && !f.excludeLocalityIds().isEmpty()) {
      // LEFT JOIN: a listing without a property has a NULL locality and must survive the exclusion
      where.append(" AND (p.locality_id IS NULL OR p.locality_id NOT IN (:excludeLocalityIds))");
      params.put("excludeLocalityIds", f.excludeLocalityIds());
    }
    appendGeoRings(f, where, params);
    if (f.budgetMin() != null) {
      where.append(" AND l.rent_monthly >= :budgetMin");
      params.put("budgetMin", f.budgetMin());
    }
    if (f.budgetMax() != null) {
      where.append(" AND l.rent_monthly <= :budgetMax");
      params.put("budgetMax", f.budgetMax());
    }
    if (f.maxDeposit() != null) {
      where.append(" AND l.deposit <= :maxDeposit");
      params.put("maxDeposit", f.maxDeposit());
    }
    if (f.roomType() != null) {
      where.append(" AND l.room_type = CAST(:roomType AS room_type)");
      params.put("roomType", f.roomType().name());
    }
    if (f.listingTypes() != null && !f.listingTypes().isEmpty()) {
      where.append(" AND l.type IN (");
      List<ListingType> types = f.listingTypes();
      for (int i = 0; i < types.size(); i++) {
        if (i > 0) where.append(',');
        where.append("CAST(:type").append(i).append(" AS listing_type)");
        params.put("type" + i, types.get(i).name());
      }
      where.append(")");
    }
    if (f.furnishings() != null && !f.furnishings().isEmpty()) {
      where.append(" AND l.furnishing IN (");
      List<Furnishing> furns = f.furnishings();
      for (int i = 0; i < furns.size(); i++) {
        if (i > 0) where.append(',');
        where.append("CAST(:furn").append(i).append(" AS furnishing)");
        params.put("furn" + i, furns.get(i).name());
      }
      where.append(")");
    }
    if (f.bhkMin() != null) {
      where.append(" AND p.bhk >= :bhkMin");
      params.put("bhkMin", f.bhkMin());
    }
    if (f.bhkMax() != null) {
      where.append(" AND p.bhk <= :bhkMax");
      params.put("bhkMax", f.bhkMax());
    }
    if (f.moveInBy() != null) {
      where.append(" AND l.available_from <= :moveInBy");
      params.put("moveInBy", f.moveInBy());
    }
    if (f.genderPref() != null && f.genderPref() != com.flatmaite.common.domain.GenderPreference.ANY) {
      where.append(" AND l.preferred_gender IN ('ANY'::gender_preference, CAST(:genderPref AS gender_preference))");
      params.put("genderPref", f.genderPref().name());
    }
    if (Boolean.TRUE.equals(f.couplesAllowed())) {
      where.append(" AND l.couples_allowed = true");
    }
    if (Boolean.TRUE.equals(f.smokeFreeHousehold())) {
      where.append(" AND l.household_smoking IS NOT TRUE");
    }
    if (Boolean.TRUE.equals(f.petFriendly())) {
      where.append(" AND l.household_pets = true");
    }
    if (Boolean.TRUE.equals(f.vegHousehold())) {
      where.append(" AND l.household_diet IN ('VEGETARIAN'::diet, 'JAIN'::diet, 'VEGAN'::diet)");
    }
    if (f.householdSocial() != null) {
      where.append(" AND l.household_social = CAST(:social AS social_style)");
      params.put("social", f.householdSocial().name());
    }
    if (Boolean.TRUE.equals(f.verifiedOnly())) {
      where.append(
          " AND (p.is_verified = true OR EXISTS (SELECT 1 FROM verifications v"
              + " WHERE v.user_id = l.lister_id AND v.type = 'GOV_ID'::verification_type"
              + " AND v.status = 'VERIFIED'::verification_status))");
    }
    if (f.amenitySlugs() != null && !f.amenitySlugs().isEmpty()) {
      where.append(
          " AND (SELECT count(*) FROM listing_amenities la JOIN amenities a ON a.id = la.amenity_id"
              + " WHERE la.listing_id = l.id AND a.slug IN (:amenitySlugs)) = :amenityCount");
      params.put("amenitySlugs", f.amenitySlugs());
      params.put("amenityCount", f.amenitySlugs().size());
    }
    return where.toString();
  }

  /**
   * Holds each admitted property to the ring the page's heading names (spec §4.1, ruling R14).
   * Locality admission is centroid-granular, so without this a property at the far edge of a
   * locality 4.9 km away renders "6.3 km from Kandivali" under a heading claiming 5 km.
   *
   * <p><b>{@code earth_box} is a bounding cube, not a circle</b> — on its own it would admit a
   * corner property up to √3 × the radius out, which is the same lie in a smaller font. It is
   * paired with {@code earth_distance}, which trims the corners to the exact ring, and the pair is
   * what makes the figure in the heading true of every row under it.
   *
   * <p>{@code earth_box ... @>} is also the only <em>index-compatible</em> half of that pair:
   * standing alone it plans as {@code Index Scan using idx_properties_geo}, which
   * {@code earth_distance} never can. It does not get that plan <em>here</em>, though, and the
   * comment should not pretend otherwise: this clause is a disjunction (the two escapes below), and
   * the join is driven by {@code properties_pkey} from the listing side, so Postgres evaluates the
   * whole thing as a per-row {@code Filter} — with {@code enable_seqscan = off} as well, so it is
   * not merely an artefact of the seed's size. It is cheap to evaluate, not index-accelerated, and
   * the index earns its keep only if a future query applies the ring on its own.
   *
   * <p>Two escapes, both deliberate and both {@code OR}-ed in ahead of the rings:
   *
   * <ul>
   *   <li>A property with no {@code lat}/{@code lng} falls back to its locality centroid, which
   *       admission has already vetted, and still appears (spec §4.9). Dropping it would be a
   *       missing coordinate silently deleting a listing.
   *   <li>A property inside a locality the user actually <em>named</em> is in the place they asked
   *       for, however far from its centroid it sits. The ring exists to police localities admitted
   *       <em>by proximity</em>, which is where the distance claim is made.
   * </ul>
   */
  private static void appendGeoRings(ListingFilters f, StringBuilder where, Map<String, Object> params) {
    List<ListingFilters.GeoRing> rings = f.geoRings();
    if (rings == null || rings.isEmpty()) {
      return;
    }
    where.append(" AND (p.lat IS NULL OR p.lng IS NULL");
    if (f.geoExemptLocalityIds() != null && !f.geoExemptLocalityIds().isEmpty()) {
      where.append(" OR p.locality_id IN (:geoExemptIds)");
      params.put("geoExemptIds", f.geoExemptLocalityIds());
    }
    for (int i = 0; i < rings.size(); i++) {
      where.append(
          (" OR (earth_box(ll_to_earth(:geoLat%1$d, :geoLng%1$d), :geoM%1$d) @> ll_to_earth(p.lat, p.lng)"
                  + " AND earth_distance(ll_to_earth(:geoLat%1$d, :geoLng%1$d), ll_to_earth(p.lat, p.lng))"
                  + " <= :geoM%1$d)")
              .formatted(i));
      params.put("geoLat" + i, rings.get(i).lat());
      params.put("geoLng" + i, rings.get(i).lng());
      params.put("geoM" + i, rings.get(i).meters());
    }
    where.append(")");
  }
}
