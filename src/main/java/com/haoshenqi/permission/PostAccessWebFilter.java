package com.haoshenqi.permission;

import java.net.URI;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.AnonymousUserConst;
import run.halo.app.security.AdditionalWebFilter;

@Component
public class PostAccessWebFilter implements AdditionalWebFilter {

    private final ReactiveExtensionClient client;

    public PostAccessWebFilter(ReactiveExtensionClient client) {
        this.client = client;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!shouldCheck(exchange)) {
            return chain.filter(exchange);
        }

        var requestPath = normalizePath(exchange.getRequest().getURI().getPath());
        return findPostByPath(requestPath)
            .flatMap(post -> currentUsername()
                .flatMap(username -> authorize(post, username))
                .flatMap(allowed -> allowed ? chain.filter(exchange) : reject(exchange, post)))
            .switchIfEmpty(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    private boolean shouldCheck(ServerWebExchange exchange) {
        var method = exchange.getRequest().getMethod();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            return false;
        }
        var path = exchange.getRequest().getURI().getPath();
        return !path.equals("/")
            && !path.startsWith("/console")
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

    private Mono<String> currentUsername() {
        return ReactiveSecurityContextHolder.getContext()
            .map(context -> context.getAuthentication())
            .filter(Authentication::isAuthenticated)
            .map(Authentication::getName)
            .filter(username -> !AnonymousUserConst.isAnonymousUser(username))
            .defaultIfEmpty("");
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
        var bytes = ("<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<title>访问受限</title></head><body><h1>访问受限</h1><p>"
            + message
            + "</p></body></html>").getBytes();
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
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
