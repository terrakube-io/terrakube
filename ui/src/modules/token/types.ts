export type CreateTokenForm = {
  description: string;
  days: number;
  hours: number;
  minutes: number;
};

export type CreatedToken = {
  token: string;
};
