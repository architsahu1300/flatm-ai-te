-- V3__geo.sql — distance becomes a first-class quantity (WS6 §4.1, §4.10)

CREATE EXTENSION IF NOT EXISTS cube;
CREATE EXTENSION IF NOT EXISTS earthdistance;

-- radius filtering runs against per-property points, not locality centroids
CREATE INDEX idx_properties_geo ON properties USING gist (ll_to_earth(lat, lng));

-- a locality name is unique within its city: Bangalore and Pune both have an
-- Indiranagar, and Delhi NCR and Navi Mumbai both have a Sector 15.
ALTER TABLE localities DROP CONSTRAINT localities_name_key;
ALTER TABLE localities ADD CONSTRAINT localities_city_name_key UNIQUE (city, name);
