package com.kunkunyu.post.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.kunkunyu.post.flow.extension.Follow;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.util.UriComponentsBuilder;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.event.post.PostPublishedEvent;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;

import static com.kunkunyu.post.flow.NotificationReasonConst.SUBSCRIBE_TO_NEW_POST;
import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;
import static org.springframework.data.domain.Sort.Order.desc;
import static run.halo.app.extension.MetadataUtil.nullSafeAnnotations;
import static run.halo.app.extension.index.query.QueryFactory.and;
import static run.halo.app.extension.index.query.QueryFactory.equal;
import static run.halo.app.extension.index.query.QueryFactory.isNull;

@Component
@RequiredArgsConstructor
public class NotificationReasonPublisher {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.systemDefault());

    private final ExtensionClient client;

    private final PostPublishedNoticeReasonPublisher postPublishedNoticeReasonPublisher;

    public static final String NEW_POST_NOTIFIED_ANNO = "flow.post.kunkunyu.com/new-post-notified";

    @Async
    @EventListener(PostPublishedEvent.class)
    public void onPostPublished(PostPublishedEvent event) {
        client.fetch(Post.class, event.getName())
            .ifPresent(post -> {
                var annotations = nullSafeAnnotations(post);

                var newPostNotified = annotations.getOrDefault(NEW_POST_NOTIFIED_ANNO,"false");

                // 获取当前时间
                Instant now = Instant.now();
                // 获取发布时间
                Instant publishTime = post.getSpec().getPublishTime();

                Duration duration = Duration.between(publishTime, now);

                var visible = post.getSpec().getVisible();
                if (Objects.equals(newPostNotified,"false") &&
                    duration.getSeconds()  <= 300 &&
                    visible.equals(Post.VisibleEnum.PUBLIC)) {

                    String owner = post.getSpec().getOwner();
                    client.fetch(User.class, owner)
                        .ifPresent(user -> {
                            String postOwner = user.getSpec().getDisplayName();
                            var listOptions = new ListOptions();
                            listOptions.setFieldSelector(FieldSelector.of(
                                and(equal("status", Follow.FollowStatus.confirm.name()),
                                    isNull("metadata.deletionTimestamp"))
                            ));
                            client.listAll(Follow.class,listOptions, Sort.by(desc("metadata.creationTimestamp"),desc("metadata.name")))
                                .forEach(follow -> postPublishedNoticeReasonPublisher.publishReasonBy(post, follow, postOwner));
                            annotations.put(NEW_POST_NOTIFIED_ANNO,"true");
                            client.update(post);
                        });
                }
            });
    }

    @Component
    @RequiredArgsConstructor
    static class PostPublishedNoticeReasonPublisher {
        private final NotificationReasonEmitter notificationReasonEmitter;

        private final ExternalLinkProcessor externalLinkProcessor;

        private final ExternalUrlSupplier externalUrlSupplier;

        public static final String UNSUBSCRIBE_PATTERN =
            "/apis/api.flow.post.kunkunyu.com/v1alpha1/follows/{name}/cancel";

        public void publishReasonBy(Post post,Follow follow, String postOwner) {
            String url = externalLinkProcessor.processLink(post.getStatus().getPermalink());
            String cancelUrl = getUnsubscribeUrl(follow);
            String title = post.getSpec().getTitle();
            String excerpt = post.getStatus().getExcerpt();
            var reasonSubject = Reason.Subject.builder()
                .apiVersion(post.getApiVersion())
                .kind(post.getKind())
                .name(post.getMetadata().getName())
                .title(post.getSpec().getTitle())
                .url(url)
                .build();
            notificationReasonEmitter.emit(SUBSCRIBE_TO_NEW_POST,
                builder -> {
                    var attributes = ReasonData.builder()
                        .email(follow.getEmail())
                        .cancelUrl(cancelUrl)
                        .postTitle(title)
                        .postPublishTime(DATE_TIME_FORMATTER.format(post.getSpec().getPublishTime()))
                        .postOwner(postOwner)
                        .postUrl(url)
                        .postExcerpt(excerpt)
                        .build();
                    builder.attributes(ReasonDataConverter.toAttributeMap(attributes))
                        .author(UserIdentity.anonymousWithEmail(follow.getEmail()))
                        .subject(reasonSubject);
                }).block();
        }


        @Builder
        record ReasonData(String email, String cancelUrl, String postTitle, String postExcerpt,
                          String postUrl, String postPublishTime, String postOwner) {
        }

        public String getUnsubscribeUrl(Follow follow) {
            var name = follow.getMetadata().getName();
            var token = follow.getToken();
            var externalUrl = defaultIfNull(externalUrlSupplier.getRaw(), URI.create("/"));
            return UriComponentsBuilder.fromUriString(externalUrl.toString())
                .path(UNSUBSCRIBE_PATTERN)
                .queryParam("token", token)
                .build(name)
                .toString();
        }
    }

    @UtilityClass
    static class ReasonDataConverter {
        public static <T> Map<String, Object> toAttributeMap(T data) {
            Assert.notNull(data, "Reason attributes must not be null");
            return JsonUtils.mapper().convertValue(data, new TypeReference<>() {
            });
        }
    }
}
