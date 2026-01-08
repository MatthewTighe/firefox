package org.mozilla.geckoview;

import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.gecko.util.GeckoBundle;

import java.util.Objects;

public class PageExtractorController {

  private static final String LOGTAG = "PageExtractorController";
  private static final boolean DEBUG = true;

  public static class SessionPageExtractor {
    private static final String GET_TEXT_EVENT = "GeckoView:PageExtractor:GetText";

    private final GeckoSession mSession;

    public SessionPageExtractor(GeckoSession mSession) {
      this.mSession = mSession;
    }

    @AnyThread
    public @NonNull GeckoResult<PageExtractorResult> getPageContent(
        @NonNull final String url
    ) {
      if (DEBUG) {
        Log.d(
            LOGTAG,
            "Page content requested for: "
                + url
        );
      }

      final GeckoBundle bundle = new GeckoBundle(1);
      bundle.putString("url", url);

      return mSession
          .getEventDispatcher()
          .queryBundle(GET_TEXT_EVENT, bundle)
          .map(result -> {
            String text = result != null ? result.getString("text") : null;

            if (DEBUG) {
              Log.d(
                  LOGTAG,
                  "Page content received for: "
                      + url
                      + " text: "
                      + text
              );
            }

            return new PageExtractorResult(text);
          });
    }
  }

  public static class PageExtractorResult {
    @Nullable
    private final String mText;

    public PageExtractorResult(@Nullable String text) {
      mText = text;
    }

    @Nullable
    public String getText() {
      return mText;
    }

    @Override
    public final boolean equals(Object o) {
      if (!(o instanceof PageExtractorResult that)) return false;

      return Objects.equals(mText, that.mText);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(mText);
    }
  }
}
