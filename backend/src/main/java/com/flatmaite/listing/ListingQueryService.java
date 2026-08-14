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
   * AI pipeline (which appends vector ordering on top).
   */
  public static String buildWhere(ListingFilters f, Map<String, Object> params) {
    StringBuilder where =
        new StringBuilder("l.deleted_at IS NULL AND l.status = 'ACTIVE'::listing_status");

    if (f.localityIds() != null && !f.localityIds().isEmpty()) {
      where.append(" AND p.locality_id IN (:localityIds)");
      params.put("localityIds", f.localityIds());
    }
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
}
