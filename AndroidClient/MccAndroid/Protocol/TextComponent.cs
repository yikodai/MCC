using System.Text;
using System.Text.RegularExpressions;

namespace MccAndroid.Protocol;

/// <summary>
/// Flattens Minecraft text components (NBT or legacy JSON shapes) into a single
/// plain-text line suitable for the Android chat view.
/// </summary>
internal static partial class TextComponent
{
    [GeneratedRegex("§.")]
    private static partial Regex FormattingCodeRegex();

    public static string ToPlainText(object? component)
    {
        var builder = new StringBuilder();
        Append(builder, component);
        return FormattingCodeRegex().Replace(builder.ToString(), string.Empty).Trim();
    }

    private static void Append(StringBuilder builder, object? component)
    {
        switch (component)
        {
            case null:
                return;

            case string text:
                builder.Append(text);
                return;

            case List<object?> list:
                foreach (var item in list)
                    Append(builder, item);
                return;

            case Dictionary<string, object?> compound:
                AppendCompound(builder, compound);
                return;
        }
    }

    private static void AppendCompound(StringBuilder builder, Dictionary<string, object?> compound)
    {
        if (compound.TryGetValue("text", out var text) && text is string textValue)
            builder.Append(textValue);
        else if (compound.TryGetValue("translate", out var translate) && translate is string translationKey)
            AppendTranslation(builder, translationKey, compound);
        else if (compound.TryGetValue("keybind", out var keybind) && keybind is string keybindValue)
            builder.Append(keybindValue);
        else if (compound.TryGetValue("selector", out var selector) && selector is string selectorValue)
            builder.Append(selectorValue);

        if (compound.TryGetValue("extra", out var extra))
            Append(builder, extra);
    }

    private static void AppendTranslation(StringBuilder builder, string translationKey, Dictionary<string, object?> compound)
    {
        // Without the client's language table the best we can do is show the
        // translation key plus its arguments, which still reads fine for chat.
        builder.Append(translationKey);

        if (compound.TryGetValue("with", out var with) && with is List<object?> arguments && arguments.Count > 0)
        {
            builder.Append('[');
            for (int i = 0; i < arguments.Count; i++)
            {
                if (i > 0)
                    builder.Append(", ");
                Append(builder, arguments[i]);
            }
            builder.Append(']');
        }
    }
}
