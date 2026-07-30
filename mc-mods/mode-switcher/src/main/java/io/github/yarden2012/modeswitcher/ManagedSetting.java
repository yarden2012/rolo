package io.github.yarden2012.modeswitcher;

import java.util.Locale;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;

/**
 * A single vanilla client option that this mod can store per profile and
 * re-apply when the player switches mode.
 *
 * <p>Values are kept as {@link JsonElement}s in the config so the class can be
 * used through a wildcard type without knowing the concrete value type.
 */
public abstract class ManagedSetting<T> {
	private final String id;
	private final Function<GameOptions, SimpleOption<T>> option;

	protected ManagedSetting(String id, Function<GameOptions, SimpleOption<T>> option) {
		this.id = id;
		this.option = option;
	}

	public String id() {
		return id;
	}

	public Text label() {
		return Text.translatable("modeswitcher.setting." + id);
	}

	/** Reads the option's current value and serializes it for storage. */
	public JsonElement captureJson(GameOptions options) {
		return serialize(option.apply(options).getValue());
	}

	/**
	 * Applies a stored value to the live game options.
	 * SimpleOption validates the value itself, so out-of-range or stale
	 * values fall back safely.
	 *
	 * @return {@code true} if the stored value could be deserialized
	 */
	public boolean applyJson(GameOptions options, JsonElement stored) {
		T value = deserialize(stored);
		if (value == null) {
			return false;
		}
		option.apply(options).setValue(value);
		return true;
	}

	/** Short human-readable form of a stored value for the config screen. */
	public String describeJson(JsonElement stored) {
		T value = stored == null ? null : deserialize(stored);
		return value == null ? "-" : describe(value);
	}

	protected abstract JsonElement serialize(T value);

	/** @return {@code null} when the stored element is malformed */
	protected abstract T deserialize(JsonElement element);

	protected String describe(T value) {
		return String.valueOf(value);
	}

	public static ManagedSetting<Integer> ofInt(String id, Function<GameOptions, SimpleOption<Integer>> option) {
		return new ManagedSetting<>(id, option) {
			@Override
			protected JsonElement serialize(Integer value) {
				return new JsonPrimitive(value);
			}

			@Override
			protected Integer deserialize(JsonElement element) {
				try {
					return element.getAsInt();
				} catch (RuntimeException e) {
					return null;
				}
			}
		};
	}

	public static ManagedSetting<Double> ofDouble(String id, Function<GameOptions, SimpleOption<Double>> option) {
		return new ManagedSetting<>(id, option) {
			@Override
			protected JsonElement serialize(Double value) {
				return new JsonPrimitive(value);
			}

			@Override
			protected Double deserialize(JsonElement element) {
				try {
					return element.getAsDouble();
				} catch (RuntimeException e) {
					return null;
				}
			}

			@Override
			protected String describe(Double value) {
				return String.format(Locale.ROOT, "%.2f", value);
			}
		};
	}

	public static ManagedSetting<Boolean> ofBoolean(String id, Function<GameOptions, SimpleOption<Boolean>> option) {
		return new ManagedSetting<>(id, option) {
			@Override
			protected JsonElement serialize(Boolean value) {
				return new JsonPrimitive(value);
			}

			@Override
			protected Boolean deserialize(JsonElement element) {
				try {
					return element.getAsBoolean();
				} catch (RuntimeException e) {
					return null;
				}
			}

			@Override
			protected String describe(Boolean value) {
				return value ? "on" : "off";
			}
		};
	}

	public static <E extends Enum<E>> ManagedSetting<E> ofEnum(String id, Class<E> type,
			Function<GameOptions, SimpleOption<E>> option) {
		return new ManagedSetting<>(id, option) {
			@Override
			protected JsonElement serialize(E value) {
				return new JsonPrimitive(value.name());
			}

			@Override
			protected E deserialize(JsonElement element) {
				try {
					return Enum.valueOf(type, element.getAsString());
				} catch (RuntimeException e) {
					return null;
				}
			}

			@Override
			protected String describe(E value) {
				return value.name().toLowerCase(Locale.ROOT);
			}
		};
	}
}
