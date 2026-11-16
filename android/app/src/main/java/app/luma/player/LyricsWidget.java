package app.luma.player;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** 홈 화면 위젯: 싱크 가사 (큰 위젯) */
public class LyricsWidget extends AppWidgetProvider {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a != null && a.startsWith("app.luma.player.W_")) {
            Widgets.onAction(context, a);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager m, int[] ids) {
        Widgets.widgetsChanged();
        Widgets.renderAll(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager m, int id, Bundle o) {
        Widgets.renderAll(context);
    }

    @Override
    public void onEnabled(Context context) { Widgets.widgetsChanged(); }

    @Override
    public void onDisabled(Context context) { Widgets.widgetsChanged(); }
}
