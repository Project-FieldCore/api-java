# API authorization matrix

Documents the authorization enforced for endpoints **currently implemented on
`develop`**. Authorization is server-side (Spring Security + `@PreAuthorize`);
client-side role checks are never sufficient.

Sites and inspection execution endpoints may land in sibling PRs — keep this
matrix in sync when those controllers merge.

| Endpoint group | ADMIN | SUPERVISOR | TECHNICIAN | CLIENT_VIEWER | Anonymous |
| --- | --- | --- | --- | --- | --- |
| `POST /api/v1/auth/login` and `POST /api/v1/auth/refresh` | Allowed | Allowed | Allowed | Allowed | Allowed |
| `POST /api/v1/auth/logout` and `GET /api/v1/auth/me` | Own session | Own session | Own session | Own session | 401 |
| `/api/v1/users` (all methods, including `/{userId}` and `/{userId}/status`) | Allowed | 403 | 403 | 403 | 401 |
| `/api/v1/clients` (all methods, including `/{clientId}/status`) | Allowed | Allowed | 403 | 403 | 401 |
| `/api/v1/equipment` CRUD + `/sites/{siteId}/equipment` + QR lookup/assign | Allowed | Allowed | 403 | 403 | 401 |
| `/api/v1/inspection-templates` (create/get/update draft) | Allowed | Allowed | 403 | 403 | 401 |

## Notes

- Non-authorized roles are denied **before** the resource is read (403
  `ACCESS_DENIED`), which prevents identifier probing (IDOR) on admin/supervisor
  resources.
- Missing resources for an authorized caller return domain 404 codes
  (`USER_NOT_FOUND`, `CLIENT_NOT_FOUND`, `SITE_NOT_FOUND`, `EQUIPMENT_NOT_FOUND`,
  `INSPECTION_TEMPLATE_NOT_FOUND`).
- User management remains ADMIN-only (PBI-0011 / matriz doc. 05). This matrix
  (PBI-0012 / RN-003) covers profile checks on every protected operation that
  exists today.
