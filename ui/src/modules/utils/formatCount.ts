// 356070234 -> "356.1M": download counts as the Terraform Registry shows them.
export const formatCount = (count: number) =>
  new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 }).format(count);
