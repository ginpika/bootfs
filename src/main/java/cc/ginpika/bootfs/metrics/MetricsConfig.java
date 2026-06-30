package cc.ginpika.bootfs.metrics;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class MetricsConfig {

    @Bean
    public TrafficStatsFilter trafficStatsFilter(RequestStatsService requestStatsService) {
        return new TrafficStatsFilter(requestStatsService);
    }

    @Bean
    public FilterRegistrationBean<TrafficStatsFilter> trafficStatsFilterRegistration(
            TrafficStatsFilter trafficStatsFilter) {
        FilterRegistrationBean<TrafficStatsFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(trafficStatsFilter);
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setName("trafficStatsFilter");
        return registration;
    }
}
