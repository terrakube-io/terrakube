package io.terrakube.api.plugin.token.login;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ConsentPageRenderer {

    private static final List<Integer> CHOICES = List.of(1, 7, 14, 30, 60, 90, 180, 365);

    // Terrakube hex-cube mark + wordmark (wordmark uses currentColor). Kept in sync with
    // ui/src/domain/Login/logo.svg.
    private static final String LOGO = """
        <svg class="logo" width="180" height="36" viewBox="0 0 240 48" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
          <polygon points="5,35 5,13 24,2 24,24" fill="#D9C7E9"/>
          <polygon points="24,2 43,13 43,35 24,24" fill="#B694D7"/>
          <polygon points="43,35 24,46 5,35 24,24" fill="#7A47B7"/>
          <text x="58" y="32" font-family="'Montserrat','Barlow',system-ui,sans-serif" font-weight="800"
                font-size="22" letter-spacing="1" fill="currentColor">TERRAKUBE</text>
        </svg>""";

    public String renderConsent(String email, int defaultDays, int maxDays, String errorMessage) {
        StringBuilder options = new StringBuilder();
        for (int d : CHOICES) {
            if (d > maxDays) {
                continue;
            }
            options.append("<option value=\"").append(d).append("\"")
                   .append(d == defaultDays ? " selected" : "")
                   .append(">").append(d).append(d == 1 ? " day" : " days").append("</option>");
        }
        String error = errorMessage == null ? ""
            : "<p class=\"error\">" + escape(errorMessage) + "</p>";
        String body = """
            <h1>Authorize CLI access</h1>
            <p class="lead">A Terraform / OpenTofu CLI on this machine is requesting an API token
               for <strong>%s</strong>. It will be able to read and write state, runs, and the
               private registry as you.</p>
            %s
            <form method="post" action="/oauth/consent">
              <label for="name">Token name <span class="hint">(optional)</span></label>
              <input id="name" name="name" type="text" maxlength="64" autocomplete="off"
                     placeholder="e.g. work-laptop">
              <label for="days">Expires after</label>
              <select id="days" name="days">%s</select>
              <div class="actions">
                <button type="submit" name="decision" value="authorize">Authorize</button>
                <button type="submit" name="decision" value="deny" class="secondary">Deny</button>
              </div>
            </form>
            <p class="foot">You can revoke this token any time from your Terrakube account settings.</p>
            """.formatted(escape(email), error, options);
        return page("Authorize Terrakube CLI login", body);
    }

    public String renderError(String message) {
        return page("Terrakube CLI login", """
            <h1>Login failed</h1>
            <p class="lead">%s</p>
            <p class="foot">You can close this window and run <code>terraform login</code> again.</p>
            """.formatted(escape(message)));
    }

    private String page(String title, String body) {
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex">
            <title>%s</title>
            <style>
              :root{
                --bg:#f5f5f7; --card:#ffffff; --border:rgba(0,0,0,.1);
                --text:#1f2430; --muted:#6b7280; --primary:#722ED1; --primary-hover:#5b25a8;
                --on-primary:#ffffff; --secondary-bg:#ececf2; --error:#b3261e; --logo:#0a0a0a;
              }
              @media (prefers-color-scheme:dark){
                :root{
                  --bg:#0d1117; --card:#161b22; --border:rgba(255,255,255,.12);
                  --text:#e6e6ef; --muted:#9aa4b2; --primary:#8b5cf6; --primary-hover:#7c46f0;
                  --on-primary:#ffffff; --secondary-bg:#21262d; --error:#f2b8b5; --logo:#ffffff;
                }
              }
              *{box-sizing:border-box}
              body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
                   padding:24px;background:var(--bg);color:var(--text);
                   font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif}
              .card{width:100%%;max-width:420px;background:var(--card);border:1px solid var(--border);
                    border-radius:12px;padding:40px 36px;box-shadow:0 4px 24px rgba(0,0,0,.06)}
              .logo{color:var(--logo);display:block;margin:0 auto 24px}
              h1{font-size:1.25rem;margin:0 0 12px;text-align:center}
              p{margin:0 0 16px;line-height:1.5}
              .lead{color:var(--text)} .foot{color:var(--muted);font-size:.85rem;margin-top:20px;margin-bottom:0}
              .hint{color:var(--muted);font-weight:400}
              label{display:block;margin:16px 0 6px;font-weight:600;font-size:.9rem}
              input,select{width:100%%;font:inherit;padding:.6rem .7rem;border-radius:8px;
                           border:1px solid var(--border);background:var(--card);color:var(--text)}
              .actions{margin-top:24px;display:flex;gap:12px}
              button{flex:1;font:inherit;font-weight:600;padding:.65rem 1rem;border:0;border-radius:8px;
                     cursor:pointer;background:var(--primary);color:var(--on-primary)}
              button:hover{background:var(--primary-hover)}
              button.secondary{background:var(--secondary-bg);color:var(--text)}
              .error{color:var(--error);font-weight:600}
              code{background:var(--secondary-bg);padding:.1rem .35rem;border-radius:4px}
            </style></head><body>
            <div class="card">
              %s
              %s
            </div>
            </body></html>
            """.formatted(escape(title), LOGO, body);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
