package com.trade.frankenstein.trader.ui.bridge;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single SSE EventSource per UI. On each message it fires a global UI event AppSseEvent
 * with (topic,json). Components can listen via:
 * ComponentUtil.addListener(UI.getCurrent(), SseBridge.AppSseEvent.class, e -> {...});
 */
public class SseBridge extends Div {

    public static final class AppSseEvent extends ComponentEvent<UI> {
        private final String topic;
        private final String json;

        public AppSseEvent(UI source, boolean fromClient, String topic, String json) {
            super(source, fromClient);
            this.topic = topic;
            this.json = json;
        }

        public String getTopic() {
            return topic;
        }

        public String getJson() {
            return json;
        }
    }

    private final Set<String> topics = new LinkedHashSet<>();
    private String sseUrl = "/api/stream";

    public SseBridge topics(String... t) {
        topics.clear();
        topics.addAll(Arrays.asList(t));
        return this;
    }

    public SseBridge url(String url) {
        this.sseUrl = url;
        return this;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        UI ui = attachEvent.getUI();
        String topicsCsv = String.join(",", topics);
        String url = sseUrl + (topicsCsv.isEmpty() ? "" : ("?topics=" + topicsCsv));

        // Open EventSource and wire to $server.onSse(topic, json)
        ui.getPage().executeJs("""
                    (function(el, url){
                      try { if (window.tfSse && window.tfSse.es) { window.tfSse.es.close(); } } catch(e){}
                      const es = new EventSource(url);
                      es.onmessage = function(e){
                        try {
                          const msg = JSON.parse(e.data);
                          if (msg && msg.topic) {
                            el.$server.onSse(String(msg.topic), e.data);
                          }
                        } catch (err) {}
                      };
                      es.onerror = function(){};
                      window.tfSse = { es };
                    })( $0, $1 );
                """, getElement(), url);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        getUI().ifPresent(ui -> ui.getPage().executeJs("""
                    try { if (window.tfSse && window.tfSse.es) { window.tfSse.es.close(); window.tfSse.es = null; } } catch(e){}
                """));
    }

    @ClientCallable
    private void onSse(String topic, String json) {
        UI ui = UI.getCurrent();
        if (ui != null) {
            ComponentUtil.fireEvent(ui, new AppSseEvent(ui, true, topic, json));
        }
    }
}
