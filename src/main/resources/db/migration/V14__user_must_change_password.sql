-- Primeiro acesso: usuários criados por um administrador recebem uma senha provisória
-- e precisam redefini-la no primeiro login. Usuários existentes não são afetados.
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
