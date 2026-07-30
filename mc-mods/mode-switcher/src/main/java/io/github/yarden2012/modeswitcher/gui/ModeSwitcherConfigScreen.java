package io.github.yarden2012.modeswitcher.gui;

import java.util.List;

import io.github.yarden2012.modeswitcher.ManagedSetting;
import io.github.yarden2012.modeswitcher.ManagedSettings;
import io.github.yarden2012.modeswitcher.Mode;
import io.github.yarden2012.modeswitcher.ModeSwitcherClient;
import io.github.yarden2012.modeswitcher.ModeSwitcherConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

/**
 * Lets the player tick which settings the toggle key switches and save the
 * current in-game options into the Vanilla or PvP profile.
 *
 * <p>The list is paginated with plain buttons instead of a scrolling list
 * widget to keep the screen simple.
 */
public class ModeSwitcherConfigScreen extends Screen {
	private static final int ROW_HEIGHT = 22;
	private static final int LIST_TOP = 56;
	private static final int LIST_HALF_WIDTH = 155;

	private final Screen parent;
	private int page;
	private int rowsPerPage = 1;

	public ModeSwitcherConfigScreen(Screen parent) {
		super(Text.translatable("modeswitcher.screen.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ModeSwitcherConfig config = ModeSwitcherClient.config();
		List<ManagedSetting<?>> all = ManagedSettings.ALL;

		int bottom = this.height - 52;
		rowsPerPage = Math.max(1, (bottom - 8 - LIST_TOP) / ROW_HEIGHT);
		int pages = (all.size() + rowsPerPage - 1) / rowsPerPage;
		page = Math.min(page, pages - 1);

		int listLeft = this.width / 2 - LIST_HALF_WIDTH;
		int start = page * rowsPerPage;
		int end = Math.min(all.size(), start + rowsPerPage);

		for (int i = start; i < end; i++) {
			ManagedSetting<?> setting = all.get(i);
			int y = LIST_TOP + (i - start) * ROW_HEIGHT;

			this.addDrawableChild(ButtonWidget.builder(enabledText(config.isEnabled(setting.id())), button -> {
				boolean enabled = !config.isEnabled(setting.id());
				config.setEnabled(setting.id(), enabled);
				config.save();
				button.setMessage(enabledText(enabled));
			}).dimensions(listLeft, y, 40, 20).build());
		}

		this.addDrawableChild(ButtonWidget.builder(Text.translatable("modeswitcher.screen.capture_vanilla"),
				button -> capture(Mode.VANILLA)).dimensions(this.width / 2 - 155, bottom, 150, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("modeswitcher.screen.capture_pvp"),
				button -> capture(Mode.PVP)).dimensions(this.width / 2 + 5, bottom, 150, 20).build());

		ButtonWidget prevButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> {
			page--;
			clearAndInit();
		}).dimensions(this.width / 2 - 155, bottom + 24, 40, 20).build());
		this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
				.dimensions(this.width / 2 - 100, bottom + 24, 200, 20).build());
		ButtonWidget nextButton = this.addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> {
			page++;
			clearAndInit();
		}).dimensions(this.width / 2 + 115, bottom + 24, 40, 20).build());

		prevButton.active = page > 0;
		nextButton.active = page < pages - 1;
	}

	private static Text enabledText(boolean enabled) {
		return Text.translatable(enabled ? "modeswitcher.screen.on" : "modeswitcher.screen.off");
	}

	private void capture(Mode mode) {
		ModeSwitcherConfig config = ModeSwitcherClient.config();
		config.captureProfile(mode, this.client.options);
		config.save();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		ModeSwitcherConfig config = ModeSwitcherClient.config();
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("modeswitcher.screen.current", config.currentMode().label()),
				this.width / 2, 27, 0xFFA0A0A0);
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("modeswitcher.screen.hint"),
				this.width / 2, 40, 0xFF808080);

		List<ManagedSetting<?>> all = ManagedSettings.ALL;
		int listLeft = this.width / 2 - LIST_HALF_WIDTH;
		int listRight = this.width / 2 + LIST_HALF_WIDTH;
		int start = page * rowsPerPage;
		int end = Math.min(all.size(), start + rowsPerPage);

		for (int i = start; i < end; i++) {
			ManagedSetting<?> setting = all.get(i);
			int textY = LIST_TOP + (i - start) * ROW_HEIGHT + 6;

			context.drawTextWithShadow(this.textRenderer, setting.label(), listLeft + 46, textY, 0xFFFFFFFF);

			String values = Text.translatable("modeswitcher.screen.values",
					setting.describeJson(config.storedValue(Mode.VANILLA, setting.id())),
					setting.describeJson(config.storedValue(Mode.PVP, setting.id()))).getString();
			context.drawTextWithShadow(this.textRenderer, values,
					listRight - this.textRenderer.getWidth(values), textY, 0xFFA0A0A0);
		}
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
