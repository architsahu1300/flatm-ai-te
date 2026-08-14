-- ============================================================
-- Flatm'AI'te full schema. Flyway owns DDL (jpa ddl-auto=none).
-- vector(1536) + tsvector columns are managed via JdbcTemplate,
-- never through JPA entities.
-- ============================================================

-- ---------- Enums ----------
CREATE TYPE user_role            AS ENUM ('USER','ADMIN');
CREATE TYPE gender               AS ENUM ('MALE','FEMALE','NON_BINARY','PREFER_NOT_TO_SAY');
CREATE TYPE listing_type         AS ENUM ('ENTIRE_APARTMENT','PRIVATE_ROOM','SHARED_ROOM','LOOKING_FOR_FLATMATE','REPLACEMENT');
CREATE TYPE listing_status       AS ENUM ('DRAFT','ACTIVE','PAUSED','RENTED','EXPIRED','REMOVED');
CREATE TYPE room_type            AS ENUM ('PRIVATE','SHARED','ENTIRE');
CREATE TYPE furnishing           AS ENUM ('UNFURNISHED','SEMI_FURNISHED','FULLY_FURNISHED');
CREATE TYPE property_type        AS ENUM ('APARTMENT','INDEPENDENT_HOUSE','PG','STUDIO');
CREATE TYPE gender_preference    AS ENUM ('MALE_ONLY','FEMALE_ONLY','ANY');
CREATE TYPE smoking_habit        AS ENUM ('NEVER','OCCASIONALLY','REGULARLY');
CREATE TYPE drinking_habit       AS ENUM ('NEVER','SOCIALLY','REGULARLY');
CREATE TYPE diet                 AS ENUM ('VEGETARIAN','EGGETARIAN','NON_VEGETARIAN','VEGAN','JAIN');
CREATE TYPE pets_stance          AS ENUM ('HAS_PETS','LOVES_PETS','OK_WITH_PETS','NO_PETS');
CREATE TYPE sleep_schedule       AS ENUM ('EARLY_BIRD','FLEXIBLE','NIGHT_OWL');
CREATE TYPE wfh_frequency        AS ENUM ('NEVER','HYBRID','FULL_TIME');
CREATE TYPE cleanliness_level    AS ENUM ('RELAXED','AVERAGE','VERY_TIDY');
CREATE TYPE social_style         AS ENUM ('VERY_SOCIAL','BALANCED','QUIET');
CREATE TYPE party_frequency      AS ENUM ('NEVER','OCCASIONALLY','FREQUENTLY');
CREATE TYPE guest_frequency      AS ENUM ('RARELY','SOMETIMES','OFTEN');
CREATE TYPE cooking_frequency    AS ENUM ('NEVER','SOMETIMES','DAILY');
CREATE TYPE occupation_type      AS ENUM ('STUDENT','WORKING_PROFESSIONAL','FREELANCER','BUSINESS_OWNER','OTHER');
CREATE TYPE conversation_status  AS ENUM ('PENDING','ACCEPTED','REJECTED','BLOCKED');
CREATE TYPE report_reason        AS ENUM ('SCAM','FAKE_LISTING','HARASSMENT','INAPPROPRIATE_CONTENT','SPAM','OTHER');
CREATE TYPE report_status        AS ENUM ('OPEN','UNDER_REVIEW','RESOLVED','DISMISSED');
CREATE TYPE verification_type    AS ENUM ('PHONE','EMAIL','GOV_ID','SELFIE','PROPERTY');
CREATE TYPE verification_status  AS ENUM ('UNVERIFIED','PENDING','VERIFIED','REJECTED');
CREATE TYPE agreement_status     AS ENUM ('DRAFT','UNDER_REVIEW','FINALIZED','SIGNED','CANCELLED');
CREATE TYPE payment_status       AS ENUM ('CREATED','PENDING','SUCCEEDED','FAILED','REFUNDED');
CREATE TYPE plan_tier            AS ENUM ('FREE','PREMIUM');
CREATE TYPE subscription_status  AS ENUM ('ACTIVE','CANCELLED','EXPIRED');
CREATE TYPE notification_type    AS ENUM ('MESSAGE','MESSAGE_REQUEST','SAVED_SEARCH_ALERT','LISTING_STATUS','AGREEMENT','VERIFICATION','SYSTEM');
CREATE TYPE notification_channel AS ENUM ('IN_APP','EMAIL','SMS');
CREATE TYPE ai_feature           AS ENUM ('INTENT_EXTRACTION','REFINEMENT','EXPLANATION','COMPARISON','AGREEMENT_DRAFT');
CREATE TYPE search_target        AS ENUM ('PROPERTIES','FLATMATES','BOTH');
CREATE TYPE order_kind           AS ENUM ('SUBSCRIPTION','BOOST','AGREEMENT_FEE','VERIFICATION_FEE');

-- ---------- Reference ----------
CREATE TABLE localities (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name        varchar(80)  NOT NULL UNIQUE,
  city        varchar(40)  NOT NULL DEFAULT 'Mumbai',
  lat         double precision NOT NULL,
  lng         double precision NOT NULL,
  aliases     text[]       NOT NULL DEFAULT '{}',
  created_at  timestamptz  NOT NULL DEFAULT now(),
  updated_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE amenities (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug        varchar(50)  NOT NULL UNIQUE,
  label       varchar(80)  NOT NULL,
  category    varchar(40),
  created_at  timestamptz  NOT NULL DEFAULT now(),
  updated_at  timestamptz  NOT NULL DEFAULT now()
);

-- ---------- Users & profiles ----------
CREATE TABLE users (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email             varchar(255) UNIQUE,
  email_verified_at timestamptz,
  phone             varchar(15) UNIQUE,
  phone_verified_at timestamptz,
  password_hash     varchar(255),
  name              varchar(120) NOT NULL,
  image             text,
  role              user_role    NOT NULL DEFAULT 'USER',
  is_suspended      boolean      NOT NULL DEFAULT false,
  last_active_at    timestamptz,
  deleted_at        timestamptz,
  created_at        timestamptz  NOT NULL DEFAULT now(),
  updated_at        timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_last_active ON users (last_active_at);

CREATE TABLE profiles (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id               uuid NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  date_of_birth         date,
  gender                gender,
  occupation            occupation_type,
  occupation_detail     varchar(120),
  company_or_college    varchar(120),
  languages             text[] NOT NULL DEFAULT '{}',
  bio                   text,
  current_locality_id   uuid REFERENCES localities(id),
  hometown              varchar(120),
  smoking               smoking_habit,
  drinking              drinking_habit,
  diet                  diet,
  pets                  pets_stance,
  sleep_schedule        sleep_schedule,
  wfh_frequency         wfh_frequency,
  cleanliness           cleanliness_level,
  social_style          social_style,
  party_frequency       party_frequency,
  guest_frequency       guest_frequency,
  cooking_frequency     cooking_frequency,
  household_pref        text,
  profile_completeness  smallint NOT NULL DEFAULT 0,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_preferences (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          uuid NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  budget_min       integer,
  budget_max       integer,
  locality_ids     uuid[] NOT NULL DEFAULT '{}',
  move_in_from     date,
  move_in_to       date,
  lease_months_min smallint,
  lease_months_max smallint,
  room_type        room_type,
  furnishing       text[] NOT NULL DEFAULT '{}',
  bhk_min          smallint,
  bhk_max          smallint,
  deposit_max      integer,
  parking_needed   boolean NOT NULL DEFAULT false,
  gender_pref      gender_preference NOT NULL DEFAULT 'ANY',
  amenities        text[] NOT NULL DEFAULT '{}',
  notes            text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now()
);

-- ---------- Properties & listings ----------
CREATE TABLE properties (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id         uuid NOT NULL REFERENCES users(id),
  locality_id      uuid NOT NULL REFERENCES localities(id),
  address_line     varchar(255) NOT NULL,
  society_name     varchar(120),
  pincode          varchar(6),
  lat              double precision,
  lng              double precision,
  property_type    property_type NOT NULL DEFAULT 'APARTMENT',
  bhk              smallint NOT NULL,
  total_bathrooms  smallint,
  floor_number     smallint,
  total_floors     smallint,
  built_up_sqft    integer,
  age_years        smallint,
  is_verified      boolean NOT NULL DEFAULT false,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_properties_owner ON properties (owner_id);
CREATE INDEX idx_properties_locality ON properties (locality_id);

CREATE TABLE listings (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  property_id         uuid REFERENCES properties(id),
  lister_id           uuid NOT NULL REFERENCES users(id),
  type                listing_type NOT NULL,
  status              listing_status NOT NULL DEFAULT 'DRAFT',
  title               varchar(140) NOT NULL,
  description         text NOT NULL DEFAULT '',
  rent_monthly        integer NOT NULL,
  deposit             integer NOT NULL DEFAULT 0,
  maintenance_monthly integer NOT NULL DEFAULT 0,
  available_from      date NOT NULL,
  min_lease_months    smallint NOT NULL DEFAULT 11,
  max_occupants       smallint,
  room_type           room_type NOT NULL,
  furnishing          furnishing NOT NULL DEFAULT 'UNFURNISHED',
  bathroom_attached   boolean,
  balcony             boolean,
  preferred_gender    gender_preference NOT NULL DEFAULT 'ANY',
  couples_allowed     boolean NOT NULL DEFAULT false,
  household_smoking   boolean,
  household_pets      boolean,
  household_diet      diet,
  household_social    social_style,
  occupants_desc      text,
  embedding           vector(1536),
  embedding_text_hash varchar(64),
  search_tsv          tsvector GENERATED ALWAYS AS (
                        to_tsvector('english', coalesce(title,'') || ' ' || coalesce(description,''))
                      ) STORED,
  quality_score       real NOT NULL DEFAULT 0,
  view_count          integer NOT NULL DEFAULT 0,
  is_boosted          boolean NOT NULL DEFAULT false,
  boosted_until       timestamptz,
  scam_risk_score     real NOT NULL DEFAULT 0,
  expires_at          timestamptz,
  deleted_at          timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_listings_status_type ON listings (status, type);
CREATE INDEX idx_listings_rent ON listings (rent_monthly);
CREATE INDEX idx_listings_available_from ON listings (available_from);
CREATE INDEX idx_listings_lister ON listings (lister_id);
CREATE INDEX idx_listings_property ON listings (property_id);
CREATE INDEX idx_listings_active ON listings (updated_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_listings_tsv ON listings USING GIN (search_tsv);
CREATE INDEX idx_listings_embedding ON listings USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

CREATE TABLE listing_images (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  listing_id  uuid NOT NULL REFERENCES listings(id) ON DELETE CASCADE,
  url         text NOT NULL,
  sort_order  smallint NOT NULL DEFAULT 0,
  is_cover    boolean NOT NULL DEFAULT false,
  width       integer,
  height      integer,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_listing_images ON listing_images (listing_id, sort_order);

CREATE TABLE listing_amenities (
  listing_id  uuid NOT NULL REFERENCES listings(id) ON DELETE CASCADE,
  amenity_id  uuid NOT NULL REFERENCES amenities(id) ON DELETE CASCADE,
  PRIMARY KEY (listing_id, amenity_id)
);
CREATE INDEX idx_listing_amenities_amenity ON listing_amenities (amenity_id);

-- ---------- Flatmate discovery ----------
CREATE TABLE flatmate_profiles (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id             uuid NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  headline            varchar(140) NOT NULL,
  about               text NOT NULL DEFAULT '',
  is_active           boolean NOT NULL DEFAULT false,
  has_flat            boolean NOT NULL DEFAULT false,
  budget_min          integer,
  budget_max          integer,
  locality_ids        uuid[] NOT NULL DEFAULT '{}',
  move_in_from        date,
  gender_pref         gender_preference NOT NULL DEFAULT 'ANY',
  embedding           vector(1536),
  embedding_text_hash varchar(64),
  search_tsv          tsvector GENERATED ALWAYS AS (
                        to_tsvector('english', coalesce(headline,'') || ' ' || coalesce(about,''))
                      ) STORED,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_flatmate_profiles_active ON flatmate_profiles (updated_at) WHERE is_active;
CREATE INDEX idx_flatmate_profiles_budget ON flatmate_profiles (budget_max);
CREATE INDEX idx_flatmate_profiles_tsv ON flatmate_profiles USING GIN (search_tsv);
CREATE INDEX idx_flatmate_profiles_embedding ON flatmate_profiles USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

-- ---------- Saved ----------
CREATE TABLE saved_listings (
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  listing_id  uuid NOT NULL REFERENCES listings(id) ON DELETE CASCADE,
  note        varchar(280),
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, listing_id)
);
CREATE INDEX idx_saved_listings_user ON saved_listings (user_id, created_at DESC);

CREATE TABLE saved_searches (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name              varchar(80) NOT NULL,
  intent            jsonb NOT NULL,
  alerts_enabled    boolean NOT NULL DEFAULT false,
  alert_frequency   varchar(10) NOT NULL DEFAULT 'daily',
  last_run_at       timestamptz,
  last_result_count integer,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_saved_searches_user ON saved_searches (user_id);
CREATE INDEX idx_saved_searches_alerts ON saved_searches (alerts_enabled) WHERE alerts_enabled;

-- ---------- Messaging ----------
CREATE TABLE conversations (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  listing_id      uuid REFERENCES listings(id),
  initiator_id    uuid NOT NULL REFERENCES users(id),
  recipient_id    uuid NOT NULL REFERENCES users(id),
  status          conversation_status NOT NULL DEFAULT 'PENDING',
  blocked_by      uuid REFERENCES users(id),
  last_message_at timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_conversations_participants
  ON conversations (initiator_id, recipient_id, coalesce(listing_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX idx_conversations_recipient ON conversations (recipient_id, status);
CREATE INDEX idx_conversations_initiator ON conversations (initiator_id);
CREATE INDEX idx_conversations_last_message ON conversations (last_message_at DESC);

CREATE TABLE messages (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  sender_id       uuid NOT NULL REFERENCES users(id),
  body            text NOT NULL,
  read_at         timestamptz,
  deleted_at      timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_messages_conversation ON messages (conversation_id, created_at DESC);
CREATE INDEX idx_messages_unread ON messages (conversation_id) WHERE read_at IS NULL;

CREATE TABLE user_blocks (
  blocker_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  blocked_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (blocker_id, blocked_id)
);

-- ---------- Safety ----------
CREATE TABLE reports (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reporter_id         uuid NOT NULL REFERENCES users(id),
  reported_user_id    uuid REFERENCES users(id),
  reported_listing_id uuid REFERENCES listings(id),
  reason              report_reason NOT NULL,
  details             text,
  status              report_status NOT NULL DEFAULT 'OPEN',
  admin_id            uuid REFERENCES users(id),
  resolution_note     text,
  resolved_at         timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  CHECK (reported_user_id IS NOT NULL OR reported_listing_id IS NOT NULL)
);
CREATE INDEX idx_reports_status ON reports (status);
CREATE INDEX idx_reports_listing ON reports (reported_listing_id);
CREATE INDEX idx_reports_user ON reports (reported_user_id);

CREATE TABLE verifications (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid REFERENCES users(id) ON DELETE CASCADE,
  property_id  uuid REFERENCES properties(id) ON DELETE CASCADE,
  type         verification_type NOT NULL,
  status       verification_status NOT NULL DEFAULT 'PENDING',
  provider     varchar(40) NOT NULL DEFAULT 'mock',
  provider_ref varchar(120),
  evidence     jsonb,
  reviewed_by  uuid REFERENCES users(id),
  reviewed_at  timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  CHECK (user_id IS NOT NULL OR property_id IS NOT NULL)
);
CREATE UNIQUE INDEX uq_verifications_user_type ON verifications (user_id, type) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_verifications_property_type ON verifications (property_id, type) WHERE property_id IS NOT NULL;

-- ---------- Agreements ----------
CREATE TABLE agreements (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  listing_id            uuid REFERENCES listings(id),
  property_id           uuid REFERENCES properties(id),
  landlord_id           uuid NOT NULL REFERENCES users(id),
  created_by            uuid NOT NULL REFERENCES users(id),
  tenant_ids            uuid[] NOT NULL DEFAULT '{}',
  status                agreement_status NOT NULL DEFAULT 'DRAFT',
  rent_monthly          integer NOT NULL,
  deposit               integer NOT NULL,
  duration_months       smallint NOT NULL DEFAULT 11,
  notice_period_days    smallint NOT NULL DEFAULT 30,
  lock_in_months        smallint NOT NULL DEFAULT 6,
  annual_escalation_pct numeric(4,1) NOT NULL DEFAULT 0,
  start_date            date NOT NULL,
  property_address      text,
  agreement_state       varchar(2) NOT NULL DEFAULT 'MH',
  clauses               jsonb NOT NULL DEFAULT '[]',
  stamp_duty            jsonb,
  signatures            jsonb NOT NULL DEFAULT '[]',
  current_version       integer NOT NULL DEFAULT 1,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_agreements_landlord ON agreements (landlord_id);
CREATE INDEX idx_agreements_tenants ON agreements USING GIN (tenant_ids);

CREATE TABLE agreement_versions (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  agreement_id uuid NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
  version      integer NOT NULL,
  snapshot     jsonb NOT NULL,
  pdf_path     text,
  created_by   uuid NOT NULL REFERENCES users(id),
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (agreement_id, version)
);

-- ---------- Payments (mock providers) ----------
CREATE TABLE plans (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug          varchar(30) NOT NULL UNIQUE,
  name          varchar(80) NOT NULL,
  tier          plan_tier NOT NULL DEFAULT 'FREE',
  price_monthly numeric(10,2) NOT NULL DEFAULT 0,
  features      jsonb NOT NULL DEFAULT '[]',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE subscriptions (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id              uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  plan_id              uuid NOT NULL REFERENCES plans(id),
  status               subscription_status NOT NULL DEFAULT 'ACTIVE',
  current_period_start timestamptz NOT NULL,
  current_period_end   timestamptz NOT NULL,
  cancel_at_period_end boolean NOT NULL DEFAULT false,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscriptions_user ON subscriptions (user_id);

CREATE TABLE orders (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES users(id),
  kind       order_kind NOT NULL,
  amount     numeric(12,2) NOT NULL,
  currency   varchar(3) NOT NULL DEFAULT 'INR',
  status     payment_status NOT NULL DEFAULT 'CREATED',
  metadata   jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_user ON orders (user_id);

CREATE TABLE payments (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id            uuid NOT NULL REFERENCES orders(id),
  provider            varchar(40) NOT NULL DEFAULT 'mock',
  provider_payment_id varchar(120),
  status              payment_status NOT NULL DEFAULT 'CREATED',
  amount              numeric(12,2) NOT NULL,
  failure_reason      varchar(255),
  idempotency_key     varchar(64) UNIQUE,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE invoices (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id       uuid NOT NULL REFERENCES orders(id),
  invoice_number varchar(30) NOT NULL UNIQUE,
  pdf_path       text,
  issued_at      timestamptz NOT NULL DEFAULT now(),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id    uuid NOT NULL REFERENCES payments(id),
  type          varchar(10) NOT NULL,
  amount        numeric(12,2) NOT NULL,
  balance_after numeric(12,2),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

-- ---------- Notifications ----------
CREATE TABLE notifications (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type       notification_type NOT NULL,
  channel    notification_channel NOT NULL DEFAULT 'IN_APP',
  title      varchar(140),
  body       text,
  data       jsonb NOT NULL DEFAULT '{}',
  read_at    timestamptz,
  sent_at    timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

-- ---------- Analytics & AI ----------
CREATE TABLE analytics_events (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid,
  anonymous_id varchar(64),
  event        varchar(80) NOT NULL,
  properties   jsonb NOT NULL DEFAULT '{}',
  session_id   varchar(64),
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_analytics_event ON analytics_events (event, created_at);
CREATE INDEX idx_analytics_user ON analytics_events (user_id);

CREATE TABLE ai_usage_log (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           uuid,
  anon_key          varchar(80),
  feature           ai_feature NOT NULL,
  provider          varchar(20) NOT NULL,
  model             varchar(60) NOT NULL,
  prompt_tokens     integer NOT NULL DEFAULT 0,
  completion_tokens integer NOT NULL DEFAULT 0,
  cost_usd          numeric(10,6) NOT NULL DEFAULT 0,
  latency_ms        integer,
  cache_hit         boolean NOT NULL DEFAULT false,
  success           boolean NOT NULL DEFAULT true,
  error_code        varchar(60),
  request_hash      varchar(64),
  created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_usage_user ON ai_usage_log (user_id, created_at);
CREATE INDEX idx_ai_usage_anon ON ai_usage_log (anon_key, created_at);
CREATE INDEX idx_ai_usage_feature ON ai_usage_log (feature, created_at);

CREATE TABLE ai_search_sessions (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid REFERENCES users(id),
  anon_session_id varchar(64),
  current_intent  jsonb NOT NULL,
  turns           jsonb NOT NULL DEFAULT '[]',
  last_result_ids uuid[] NOT NULL DEFAULT '{}',
  expires_at      timestamptz NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_sessions_user ON ai_search_sessions (user_id);
CREATE INDEX idx_ai_sessions_expiry ON ai_search_sessions (expires_at);
