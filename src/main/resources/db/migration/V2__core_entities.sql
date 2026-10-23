-- Core operational entities. UUIDs are assigned by the application so this
-- migration remains portable between the local H2 profile and PostgreSQL.

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    name VARCHAR(160) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    phone VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_role CHECK (role IN ('TECHNICIAN', 'SUPERVISOR', 'ADMIN', 'CLIENT_VIEWER')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLOCKED')),
    CONSTRAINT ck_app_user_version CHECK (version >= 0)
);

CREATE INDEX ix_app_user_status ON app_user (status);
CREATE INDEX ix_app_user_role ON app_user (role);

CREATE TABLE refresh_token (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_token_user_id ON refresh_token (user_id);

CREATE TABLE client (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    legal_name VARCHAR(255),
    document VARCHAR(64),
    email VARCHAR(320),
    phone VARCHAR(32),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_client_document UNIQUE (document),
    CONSTRAINT ck_client_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_client_version CHECK (version >= 0)
);

CREATE TABLE inspection_site (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    address_line VARCHAR(255),
    city VARCHAR(120),
    state VARCHAR(120),
    postal_code VARCHAR(32),
    latitude DECIMAL(10,7),
    longitude DECIMAL(10,7),
    contact_name VARCHAR(160),
    contact_phone VARCHAR(32),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_inspection_site_client FOREIGN KEY (client_id) REFERENCES client (id),
    CONSTRAINT uk_inspection_site_client_name UNIQUE (client_id, name),
    CONSTRAINT uk_inspection_site_id_client UNIQUE (id, client_id),
    CONSTRAINT ck_inspection_site_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_inspection_site_version CHECK (version >= 0)
);

CREATE INDEX ix_inspection_site_client_id ON inspection_site (client_id);

CREATE TABLE equipment (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    qr_code VARCHAR(255) NOT NULL,
    asset_number VARCHAR(100),
    serial_number VARCHAR(100),
    manufacturer VARCHAR(160),
    model VARCHAR(160),
    description VARCHAR(1000),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    installed_at DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_equipment_site FOREIGN KEY (site_id) REFERENCES inspection_site (id),
    CONSTRAINT uk_equipment_qr_code UNIQUE (qr_code),
    CONSTRAINT uk_equipment_site_serial_number UNIQUE (site_id, serial_number),
    CONSTRAINT uk_equipment_id_site UNIQUE (id, site_id),
    CONSTRAINT ck_equipment_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'DECOMMISSIONED')),
    CONSTRAINT ck_equipment_version CHECK (version >= 0)
);

CREATE INDEX ix_equipment_site_id ON equipment (site_id);
CREATE INDEX ix_equipment_status ON equipment (status);
CREATE INDEX ix_equipment_asset_number ON equipment (asset_number);
