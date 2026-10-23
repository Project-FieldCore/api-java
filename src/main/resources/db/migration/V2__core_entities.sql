-- Core operational entities. UUIDs are assigned by the application so this
-- migration remains portable between the local H2 profile and PostgreSQL.

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    full_name VARCHAR(160) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_role CHECK (role IN ('TECHNICIAN', 'SUPERVISOR', 'ADMIN'))
);

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
    external_reference VARCHAR(100),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_client_external_reference UNIQUE (external_reference)
);

CREATE TABLE inspection_site (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    address_line VARCHAR(255),
    city VARCHAR(120),
    state VARCHAR(120),
    postal_code VARCHAR(32),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inspection_site_client FOREIGN KEY (client_id) REFERENCES client (id),
    CONSTRAINT uk_inspection_site_client_name UNIQUE (client_id, name)
);

CREATE INDEX ix_inspection_site_client_id ON inspection_site (client_id);

CREATE TABLE equipment (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    qr_code VARCHAR(255) NOT NULL,
    serial_number VARCHAR(100),
    equipment_type VARCHAR(100),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_equipment_site FOREIGN KEY (site_id) REFERENCES inspection_site (id),
    CONSTRAINT uk_equipment_qr_code UNIQUE (qr_code),
    CONSTRAINT uk_equipment_site_serial_number UNIQUE (site_id, serial_number)
);

CREATE INDEX ix_equipment_site_id ON equipment (site_id);
