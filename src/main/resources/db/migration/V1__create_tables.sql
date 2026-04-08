-- =============================================================
-- V1 – Create all tables
-- =============================================================

-- ---------------------------------------------------------------
-- categories (supports 3-level hierarchy via self-referencing FK)
-- ---------------------------------------------------------------
CREATE TABLE categories (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    slug        VARCHAR(120)  UNIQUE NOT NULL,
    parent_id   BIGINT        REFERENCES categories(id),
    level       INT           NOT NULL,
    image_url   VARCHAR(500),
    sort_order  INT           NOT NULL DEFAULT 0,
    is_active   BOOLEAN       NOT NULL DEFAULT true,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_categories_slug      ON categories(slug);
CREATE INDEX idx_categories_parent_id ON categories(parent_id);

-- ---------------------------------------------------------------
-- products
-- ---------------------------------------------------------------
CREATE TABLE products (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(300)   NOT NULL,
    slug           VARCHAR(350)   UNIQUE NOT NULL,
    description    TEXT,
    highlights     TEXT,
    box_break_info TEXT,
    configuration  VARCHAR(500),
    price          DECIMAL(10,2),
    original_price DECIMAL(10,2),
    brand          VARCHAR(100),
    is_on_sale     BOOLEAN        NOT NULL DEFAULT false,
    is_new         BOOLEAN        NOT NULL DEFAULT false,
    is_trending    BOOLEAN        NOT NULL DEFAULT false,
    is_best_seller BOOLEAN        NOT NULL DEFAULT false,
    is_pre_order   BOOLEAN        NOT NULL DEFAULT false,
    category_id    BIGINT         NOT NULL REFERENCES categories(id),
    sort_order     INT            NOT NULL DEFAULT 0,
    is_active      BOOLEAN        NOT NULL DEFAULT true,
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_products_slug        ON products(slug);
CREATE INDEX idx_products_category_id ON products(category_id);
CREATE INDEX idx_products_is_active   ON products(is_active);

-- ---------------------------------------------------------------
-- product_images
-- ---------------------------------------------------------------
CREATE TABLE product_images (
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT       NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    image_url  VARCHAR(500) NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    is_primary BOOLEAN      NOT NULL DEFAULT false
);

-- ---------------------------------------------------------------
-- inquiries
-- ---------------------------------------------------------------
CREATE TABLE inquiries (
    id               BIGSERIAL PRIMARY KEY,
    product_id       BIGINT       REFERENCES products(id),
    customer_name    VARCHAR(100) NOT NULL,
    customer_email   VARCHAR(200) NOT NULL,
    customer_phone   VARCHAR(50),
    customer_company VARCHAR(200),
    quantity         INT,
    message          TEXT,
    status           VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_inquiries_status ON inquiries(status);

-- ---------------------------------------------------------------
-- banners
-- ---------------------------------------------------------------
CREATE TABLE banners (
    id         BIGSERIAL PRIMARY KEY,
    title      VARCHAR(200),
    image_url  VARCHAR(500) NOT NULL,
    link_url   VARCHAR(500),
    sort_order INT          NOT NULL DEFAULT 0,
    is_active  BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------
-- admin_users  (BCrypt-encoded passwords)
-- ---------------------------------------------------------------
CREATE TABLE admin_users (
    id        BIGSERIAL PRIMARY KEY,
    username  VARCHAR(50)  UNIQUE NOT NULL,
    password  VARCHAR(200) NOT NULL,
    role      VARCHAR(20)  NOT NULL DEFAULT 'ADMIN',
    is_active BOOLEAN      NOT NULL DEFAULT true
);
