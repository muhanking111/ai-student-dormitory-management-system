export interface KeywordRecord {
  [key: string]: string | number | boolean | null | undefined
}

export function filterByKeyword<T extends KeywordRecord>(rows: T[], keyword: string, keys: Array<keyof T>): T[] {
  const normalized = keyword.trim().toLowerCase()
  if (!normalized) {
    return rows
  }

  return rows.filter((row) =>
    keys.some((key) => String(row[key] ?? '').toLowerCase().includes(normalized)),
  )
}

export function formatCurrency(amount: number): string {
  return amount.toFixed(2)
}
