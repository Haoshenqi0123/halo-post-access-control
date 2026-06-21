package com.haoshenqi.permission;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.AnonymousUserConst;
import run.halo.app.security.AfterSecurityWebFilter;

@Component
public class PostAccessWebFilter implements AfterSecurityWebFilter, Ordered {

    private final ReactiveExtensionClient client;

    public PostAccessWebFilter(ReactiveExtensionClient client) {
        this.client = client;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!shouldCheck(exchange)) {
            return chain.filter(exchange);
        }

        return findPost(exchange)
            .flatMap(post -> currentUsername(exchange)
                .flatMap(username -> authorize(post, username))
                .flatMap(allowed -> allowed ? chain.filter(exchange) : reject(exchange, post)))
            .switchIfEmpty(chain.filter(exchange));
    }

    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    private boolean shouldCheck(ServerWebExchange exchange) {
        var method = exchange.getRequest().getMethod();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            return false;
        }
        var path = exchange.getRequest().getURI().getPath();
        if (path.equals("/")) {
            return exchange.getRequest().getQueryParams().containsKey("p");
        }
        return !path.startsWith("/console")
            && !path.startsWith("/uc")
            && !path.startsWith("/apis")
            && !path.startsWith("/api")
            && !path.startsWith("/assets")
            && !path.startsWith("/plugins")
            && !path.startsWith("/upload")
            && !path.startsWith("/login")
            && !path.startsWith("/signup")
            && !path.startsWith("/actuator")
            && !path.startsWith("/favicon");
    }

    private Mono<Post> findPost(ServerWebExchange exchange) {
        var postName = exchange.getRequest().getQueryParams().getFirst("p");
        if (postName != null && !postName.isBlank()) {
            return findPostByNameOrSlug(postName)
                .switchIfEmpty(findPostByPath(normalizePath(exchange.getRequest().getURI().getPath())));
        }
        return findPostByPath(normalizePath(exchange.getRequest().getURI().getPath()));
    }

    private Mono<Post> findPostByNameOrSlug(String nameOrSlug) {
        return client.fetch(Post.class, nameOrSlug)
            .filter(this::isVisiblePost)
            .switchIfEmpty(client.list(Post.class, this::isVisiblePost, null)
                .filter(post -> post.getSpec() != null
                    && Objects.equals(nameOrSlug, post.getSpec().getSlug()))
                .next());
    }

    private Mono<Post> findPostByPath(String requestPath) {
        return client.list(Post.class, this::isVisiblePost, null)
            .filter(post -> Objects.equals(requestPath, permalinkPath(post)))
            .next();
    }

    private boolean isVisiblePost(Post post) {
        return post != null && post.isPublished() && !post.isDeleted();
    }

    private String permalinkPath(Post post) {
        var status = post.getStatusOrDefault();
        var permalink = status.getPermalink();
        if (permalink == null || permalink.isBlank()) {
            return "";
        }
        try {
            return normalizePath(URI.create(permalink).getPath());
        } catch (IllegalArgumentException e) {
            return normalizePath(permalink);
        }
    }

    private Mono<String> currentUsername(ServerWebExchange exchange) {
        return exchange.getPrincipal()
            .filter(this::isAuthenticated)
            .map(Principal::getName)
            .filter(username -> !AnonymousUserConst.isAnonymousUser(username))
            .defaultIfEmpty("");
    }

    private boolean isAuthenticated(Principal principal) {
        return !(principal instanceof Authentication authentication) || authentication.isAuthenticated();
    }

    private Mono<Boolean> authorize(Post post, String username) {
        var permission = PostAccessPermission.from(post);
        var authenticated = !username.isBlank();
        return Mono.just(switch (permission) {
            case PUBLIC -> true;
            case NORMAL -> authenticated;
            case PRIVATE -> authenticated && Objects.equals(username, post.getSpec().getOwner());
        });
    }

    private Mono<Void> reject(ServerWebExchange exchange, Post post) {
        var permission = PostAccessPermission.from(post);
        var status = permission == PostAccessPermission.NORMAL
            ? HttpStatus.UNAUTHORIZED
            : HttpStatus.FORBIDDEN;
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.TEXT_HTML);
        var message = permission == PostAccessPermission.NORMAL
            ? "此文章需要登录后访问。"
            : "此文章仅作者本人可访问。";
        var bytes = renderForbiddenPage(exchange, post, permission, message)
            .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private String renderForbiddenPage(ServerWebExchange exchange, Post post,
        PostAccessPermission permission, String message) {
        var title = post.getSpec() == null ? "受限文章" : post.getSpec().getTitle();
        var requestUri = exchange.getRequest().getURI().getRawPath();
        var rawQuery = exchange.getRequest().getURI().getRawQuery();
        if (rawQuery != null && !rawQuery.isBlank()) {
            requestUri += "?" + rawQuery;
        }
        var loginUrl = "/login?redirect_uri="
            + URLEncoder.encode(requestUri, StandardCharsets.UTF_8);
        var primaryAction = permission == PostAccessPermission.NORMAL
            ? "<a class=\"button button-primary\" href=\"" + loginUrl + "\">去登录</a>"
            : "<a class=\"button button-primary\" href=\"/\">返回首页</a>";
        var secondaryAction = permission == PostAccessPermission.NORMAL
            ? "<a class=\"button\" href=\"/\">返回首页</a>"
            : "";
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>访问受限</title>
              <style>
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  min-height: 100vh;
                  display: grid;
                  place-items: center;
                  padding: 24px;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  color: #162033;
                  background:
                    radial-gradient(circle at 20%% 12%%, rgba(64, 169, 255, .18), transparent 28%%),
                    linear-gradient(135deg, #f7fbff 0%%, #eef4fb 52%%, #f8fafc 100%%);
                }
                .panel {
                  width: min(100%%, 520px);
                  padding: 42px 38px;
                  border: 1px solid rgba(148, 163, 184, .28);
                  border-radius: 18px;
                  background: rgba(255, 255, 255, .92);
                  box-shadow: 0 24px 70px rgba(15, 23, 42, .12);
                  text-align: center;
                }
                .icon {
                  width: 64px;
                  height: 64px;
                  display: inline-grid;
                  place-items: center;
                  border-radius: 18px;
                  color: #1677ff;
                  background: #e8f3ff;
                  margin-bottom: 22px;
                }
                h1 {
                  margin: 0;
                  font-size: 28px;
                  line-height: 1.25;
                  letter-spacing: 0;
                }
                .post-title {
                  margin: 14px 0 0;
                  color: #475569;
                  font-size: 16px;
                  line-height: 1.7;
                }
                .message {
                  margin: 8px 0 0;
                  color: #64748b;
                  font-size: 15px;
                  line-height: 1.7;
                }
                .actions {
                  display: flex;
                  justify-content: center;
                  gap: 12px;
                  margin-top: 30px;
                  flex-wrap: wrap;
                }
                .button {
                  min-width: 116px;
                  padding: 11px 18px;
                  border-radius: 10px;
                  border: 1px solid #cbd5e1;
                  color: #334155;
                  background: #fff;
                  font-size: 15px;
                  font-weight: 600;
                  text-decoration: none;
                }
                .button-primary {
                  border-color: #1677ff;
                  color: #fff;
                  background: #1677ff;
                  box-shadow: 0 10px 22px rgba(22, 119, 255, .24);
                }
                @media (max-width: 520px) {
                  .panel { padding: 34px 24px; border-radius: 14px; }
                  h1 { font-size: 24px; }
                  .button { width: 100%%; }
                }
              </style>
            </head>
            <body>
              <main class="panel">
                <div class="icon" aria-hidden="true">
                  <svg width="30" height="30" viewBox="0 0 24 24" fill="none"
                    xmlns="http://www.w3.org/2000/svg">
                    <path d="M7 10V8a5 5 0 0 1 10 0v2" stroke="currentColor"
                      stroke-width="1.8" stroke-linecap="round"/>
                    <rect x="5" y="10" width="14" height="10" rx="2.5"
                      stroke="currentColor" stroke-width="1.8"/>
                    <path d="M12 14v2" stroke="currentColor" stroke-width="1.8"
                      stroke-linecap="round"/>
                  </svg>
                </div>
                <h1>访问受限</h1>
                <p class="post-title">%s</p>
                <p class="message">%s</p>
                <div class="actions">
                  %s
                  %s
                </div>
              </main>
            </body>
            </html>
            """.formatted(escapeHtml(title), escapeHtml(message), primaryAction, secondaryAction);
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        var normalized = path.startsWith("/") ? path : "/" + path;
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
