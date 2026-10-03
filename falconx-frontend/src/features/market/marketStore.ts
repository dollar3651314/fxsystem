import { create } from "zustand";
import { reduceMarketMessage, reduceMarketMessages } from "./marketSocket";
import type {
  Kline,
  KlineInterval,
  MarketConnectionState,
  MarketServerMessage,
  Quote
} from "./marketTypes";

type MarketStoreState = {
  selectedSymbol: string | null;
  connectionState: MarketConnectionState;
  quotes: Record<string, Quote>;
  quoteHistory: Record<string, Quote[]>;
  klines: Record<string, Partial<Record<KlineInterval, Kline[]>>>;
  setSelectedSymbol: (symbol: string | null) => void;
  setConnectionState: (connectionState: MarketConnectionState) => void;
  applyMarketMessage: (message: MarketServerMessage) => void;
  applyMarketMessages: (messages: MarketServerMessage[]) => void;
  reset: () => void;
};

const initialState = {
  selectedSymbol: null,
  connectionState: "idle" as MarketConnectionState,
  quotes: {},
  quoteHistory: {},
  klines: {}
};

export const useMarketStore = create<MarketStoreState>((set) => ({
  ...initialState,
  setSelectedSymbol: (selectedSymbol) => set({ selectedSymbol }),
  setConnectionState: (connectionState) => set({ connectionState }),
  applyMarketMessage: (message) =>
    set((state) =>
      reduceMarketMessage(
        {
          quotes: state.quotes,
          quoteHistory: state.quoteHistory,
          klines: state.klines
        },
        message
      )
    ),
  applyMarketMessages: (messages) =>
    set((state) =>
      reduceMarketMessages(
        {
          quotes: state.quotes,
          quoteHistory: state.quoteHistory,
          klines: state.klines
        },
        messages
      )
    ),
  reset: () => set(initialState)
}));
