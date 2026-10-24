# API authorization matrix

This matrix documents the authorization implemented for the API endpoints currently
available in the backend. Authorization is enforced server-side with Spring Security
and method security; a client-side role must never be treated as authorization.

| Endpoint group | ADMIN | SUPERVISOR | TECHNICIAN | CLIENT_VIEWER | Anonymous |
| --- | --- | --- | --- | --- | --- |
| `POST /api/v1/auth/login` and `POST /api/v1/auth/refresh` | Allowed | Allowed | Allowed | Allowed | Allowed |
| `POST /api/v1/auth/logout` and `GET /api/v1/auth/me` | Own session | Own session | Own session | Own session | 401 |
| `/api/v1/users` and `/api/v1/users/{userId}` (all methods) | Allowed | 403 | 403 | 403 | 401 |

For user management, non-administrative roles are denied before a resource is read.
This prevents identifier-based access to another user's data (IDOR). Future endpoint
groups must be added to this document together with their authorization tests.
