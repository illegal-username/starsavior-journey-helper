package helper.journey.starsavior;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** Compact details expand below the linked reward, inside its existing column. */
final class ItemDetailsView {
    private ItemDetailsView() {}

    static void bind(TextView view, LinearLayout column, List<ItemDetails.Link> links, int offset) {
        if (links.isEmpty()) return;
        InlineDetails details = new InlineDetails(column);
        SpannableString text = new SpannableString(view.getText());
        for (ItemDetails.Link link : links) {
            text.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) { details.toggle(link.item); }
            }, offset + link.start, offset + link.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(text);
        view.setLinkTextColor(0xffaebeff);
        view.setMovementMethod(LinkMovementMethod.getInstance());
    }

    static boolean hasLinks(View view) {
        if (view instanceof TextView && ((TextView) view).getText() instanceof Spanned) {
            Spanned text = (Spanned) ((TextView) view).getText();
            if (text.getSpans(0, text.length(), ClickableSpan.class).length > 0) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasLinks(group.getChildAt(i))) return true;
        }
        return false;
    }

    private static final class InlineDetails {
        private final LinearLayout column;
        private View panel;
        private String itemId;

        InlineDetails(LinearLayout column) { this.column = column; }

        void toggle(ItemDetails.Item item) {
            boolean wasOpen = item.id.equals(itemId);
            close();
            if (wasOpen) return;
            Context context = column.getContext();
            LinearLayout detail = new LinearLayout(context);
            detail.setOrientation(LinearLayout.VERTICAL);
            int padding = Ui.dp(context, 8);
            detail.setPadding(padding, 0, padding, padding);
            detail.setBackground(Ui.roundedStroke(context, Ui.CARD_ALT, 8, Ui.PRIMARY_DARK, 1));

            LinearLayout header = new LinearLayout(context);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setBaselineAligned(false);
            TextView name = Ui.text(context, item.name, 12, Ui.BLUE);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView close = Ui.text(context, "×", 22, Ui.MUTED);
            close.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
            close.setGravity(Gravity.CENTER);
            close.setContentDescription(context.getString(R.string.item_close_details));
            close.setOnClickListener(v -> close());
            header.addView(close, new LinearLayout.LayoutParams(Ui.dp(context, 36), Ui.dp(context, 36)));
            detail.addView(header, new LinearLayout.LayoutParams(-1, -2));

            section(context, detail, context.getString(R.string.item_description), item.description);
            section(context, detail, context.getString(R.string.item_effect), item.effect);
            if (item.description.isEmpty() && item.effect.isEmpty()) {
                detail.addView(Ui.text(context, context.getString(R.string.item_details_unavailable), 11, Ui.MUTED),
                        new LinearLayout.LayoutParams(-1, -2));
            }
            panel = detail;
            itemId = item.id;
            column.addView(detail, OverlayResultView.margins(context, -1, -2, 0, 5, 0, 0));
        }

        private void close() {
            if (panel != null) column.removeView(panel);
            panel = null;
            itemId = null;
        }
    }

    private static void section(Context context, LinearLayout panel, String label, String value) {
        if (value.isEmpty()) return;
        TextView heading = Ui.text(context, label, 10, Ui.ORANGE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        panel.addView(heading, OverlayResultView.margins(context, -1, -2, 0, 3, 0, 1));
        TextView body = Ui.text(context, value, 11, Ui.TEXT);
        body.setLineSpacing(0, 1.1f);
        panel.addView(body, new LinearLayout.LayoutParams(-1, -2));
    }
}
