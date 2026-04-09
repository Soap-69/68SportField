# 68 Sport Field — Trading Card Product Catalog

B2B wholesale trading card catalog built with Spring Boot 3, Thymeleaf, and PostgreSQL.

---

## Quick Start (Development)

1. Start the database: `docker start card-db`
2. Run the app in IntelliJ or: `./mvnw spring-boot:run`
3. Open: http://localhost:8080
4. Admin: http://localhost:8080/admin/login — default credentials: **admin / admin123**

---

## Production Deployment

### Prerequisites
- Docker + Docker Compose installed on your server

### Steps

```bash
# 1. Clone the repo
git clone <your-repo-url>
cd card-showcase

# 2. Configure environment
cp .env.example .env
# Edit .env with your real values (DB password, email credentials, etc.)

# 3. Deploy
chmod +x deploy.sh
./deploy.sh
```

The site will be live at **http://your-server-ip**.

### Update an existing deployment

```bash
git pull
docker compose build --no-cache
docker compose up -d
```

---

## Common Commands

| Task | Command |
|---|---|
| View app logs | `docker compose logs -f app` |
| Restart app | `docker compose restart app` |
| Stop everything | `docker compose down` |
| DB backup | `docker exec card-showcase-db pg_dump -U postgres card_showcase > backup.sql` |
| DB restore | `cat backup.sql \| docker exec -i card-showcase-db psql -U postgres card_showcase` |

---

## Admin

- **Login:** `/admin/login`
- **Default credentials:** admin / admin123
- **Change credentials:** `/admin/settings`

> **Important:** Change the default password immediately after first login.

---

## Email Notifications

Requires a Gmail account with 2FA enabled and an App Password:

1. Enable 2FA on your Gmail account
2. Generate an App Password at: https://myaccount.google.com/apppasswords
3. Set `MAIL_USERNAME`, `MAIL_PASSWORD`, and `ADMIN_EMAIL` in `.env`
4. Set `NOTIFICATION_ENABLED=true`

---

## Forgot Admin Password?

**Development (local):**
```bash
./reset-password.sh
```

**Manual SQL:**
```sql
-- BCrypt hash for 'admin123' — from V2__seed_data.sql
UPDATE admin_users
SET    username = 'admin',
       password = '$2a$10$ZKK9.C8rSdyySP.OCqAQoufV1ZhxsITNoOVd897JT5VXIANvDO9PW'
WHERE  id = 1;
```

Or generate a fresh hash for any password:
```bash
mvn compile exec:java -Dexec.mainClass="com.cardshowcase.util.PasswordResetUtil"
```

After reset, log in with **admin / admin123** and change your password at `/admin/settings`.
