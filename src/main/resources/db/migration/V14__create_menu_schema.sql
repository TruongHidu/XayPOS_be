CREATE TABLE item_groups (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 restaurant_id UUID NOT NULL REFERENCES restaurants(id),
 name VARCHAR(100) NOT NULL CHECK (btrim(name) <> ''),
 display_order INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
 is_active BOOLEAN NOT NULL DEFAULT true,
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 deleted_at TIMESTAMPTZ,
 UNIQUE (restaurant_id, id)
);
CREATE UNIQUE INDEX ux_menu_group_name ON item_groups(restaurant_id, lower(name)) WHERE deleted_at IS NULL;
CREATE TABLE items (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 restaurant_id UUID NOT NULL REFERENCES restaurants(id),
 item_group_id UUID,
 sku VARCHAR(80),
 name VARCHAR(150) NOT NULL CHECK (btrim(name) <> ''),
 item_type VARCHAR(20) NOT NULL CHECK (item_type IN ('MENU_ITEM','INGREDIENT')),
 base_unit VARCHAR(30) NOT NULL CHECK (btrim(base_unit) <> ''),
 description TEXT,
 image_url VARCHAR(500),
 sale_price NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (sale_price >= 0),
 cost_price NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (cost_price >= 0),
 track_inventory BOOLEAN NOT NULL DEFAULT false,
 availability_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' CHECK (availability_status IN ('AVAILABLE','OUT_OF_STOCK')),
 is_active BOOLEAN NOT NULL DEFAULT true,
 metadata JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(metadata) = 'object'),
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 deleted_at TIMESTAMPTZ,
 CONSTRAINT fk_menu_item_group FOREIGN KEY (restaurant_id,item_group_id) REFERENCES item_groups(restaurant_id,id),
 CONSTRAINT ck_ingredient_group CHECK (item_type <> 'INGREDIENT' OR item_group_id IS NULL),
 CONSTRAINT ck_menu_inventory CHECK (item_type <> 'MENU_ITEM' OR NOT track_inventory),
 UNIQUE (restaurant_id,id)
);
CREATE UNIQUE INDEX ux_menu_item_sku ON items(restaurant_id,sku) WHERE sku IS NOT NULL;
CREATE INDEX ix_menu_item_group ON items(restaurant_id,item_group_id);
CREATE INDEX ix_menu_item_search ON items(restaurant_id,item_type,created_at,id) WHERE deleted_at IS NULL;
