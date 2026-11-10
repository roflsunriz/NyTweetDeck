import type { Locale } from "../model/layout";

interface InteractionLabels {
  copyMedia: string;
  withoutQuote: string;
  translatedWithoutQuote: string;
  columnRemoved: string;
  undo: string;
}

const labels: Record<Locale, InteractionLabels> = {
  ja: {
    copyMedia: "メディアURLのみコピー",
    withoutQuote: "詳細形式でコピー（引用なし）",
    translatedWithoutQuote: "詳細形式でコピー（翻訳・引用なし）",
    columnRemoved: "カラムを削除しました",
    undo: "復元",
  },
  en: {
    copyMedia: "Copy media URLs only",
    withoutQuote: "Copy with details (without quote)",
    translatedWithoutQuote: "Copy with details (translated, without quote)",
    columnRemoved: "Column removed",
    undo: "Undo",
  },
  zh: {
    copyMedia: "仅复制媒体链接",
    withoutQuote: "复制详细内容（不含引用）",
    translatedWithoutQuote: "复制翻译详细内容（不含引用）",
    columnRemoved: "已删除列",
    undo: "撤销",
  },
  hi: {
    copyMedia: "केवल मीडिया URL कॉपी करें",
    withoutQuote: "विवरण कॉपी करें (उद्धरण बिना)",
    translatedWithoutQuote: "अनूदित विवरण कॉपी करें (उद्धरण बिना)",
    columnRemoved: "कॉलम हटाया गया",
    undo: "पूर्ववत करें",
  },
  es: {
    copyMedia: "Copiar solo enlaces de medios",
    withoutQuote: "Copiar detalles (sin cita)",
    translatedWithoutQuote: "Copiar detalles traducidos (sin cita)",
    columnRemoved: "Columna eliminada",
    undo: "Deshacer",
  },
  fr: {
    copyMedia: "Copier les liens des médias",
    withoutQuote: "Copier les détails (sans citation)",
    translatedWithoutQuote: "Copier les détails traduits (sans citation)",
    columnRemoved: "Colonne supprimée",
    undo: "Annuler",
  },
  ar: {
    copyMedia: "نسخ روابط الوسائط فقط",
    withoutQuote: "نسخ التفاصيل دون الاقتباس",
    translatedWithoutQuote: "نسخ التفاصيل المترجمة دون الاقتباس",
    columnRemoved: "تم حذف العمود",
    undo: "تراجع",
  },
  pt: {
    copyMedia: "Copiar apenas links de mídia",
    withoutQuote: "Copiar detalhes (sem citação)",
    translatedWithoutQuote: "Copiar detalhes traduzidos (sem citação)",
    columnRemoved: "Coluna removida",
    undo: "Desfazer",
  },
  bn: {
    copyMedia: "শুধু মিডিয়া URL কপি করুন",
    withoutQuote: "বিবরণ কপি করুন (উদ্ধৃতি ছাড়া)",
    translatedWithoutQuote: "অনূদিত বিবরণ কপি করুন (উদ্ধৃতি ছাড়া)",
    columnRemoved: "কলাম মুছে ফেলা হয়েছে",
    undo: "পূর্বাবস্থায় ফেরান",
  },
  ru: {
    copyMedia: "Копировать ссылки на медиа",
    withoutQuote: "Копировать с деталями (без цитаты)",
    translatedWithoutQuote: "Копировать перевод с деталями (без цитаты)",
    columnRemoved: "Столбец удалён",
    undo: "Отменить",
  },
  ur: {
    copyMedia: "صرف میڈیا روابط کاپی کریں",
    withoutQuote: "تفصیلات کاپی کریں (اقتباس کے بغیر)",
    translatedWithoutQuote: "ترجمہ شدہ تفصیلات کاپی کریں (اقتباس کے بغیر)",
    columnRemoved: "کالم ہٹا دیا گیا",
    undo: "واپس کریں",
  },
};

export function interactionLabels(locale: string): InteractionLabels {
  return labels[locale as Locale] ?? labels.en;
}
