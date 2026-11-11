import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import axiosInstance from "@/config/axiosConfig";
import type { Team } from "@/domain/types";

/** Query keys for an organization's teams. Invalidate `teamKeys.list(orgId)` after any change to them. */
export const teamKeys = {
  list: (organizationId: string | undefined) => ["organization", organizationId, "teams"] as const,
};

export function useTeams(organizationId: string | undefined) {
  return useQuery({
    queryKey: teamKeys.list(organizationId),
    queryFn: async (): Promise<Team[]> => {
      const response = await axiosInstance.get(`organization/${organizationId}/team`);
      return response.data.data;
    },
    enabled: Boolean(organizationId),
  });
}

/** Refetches the team list; call it after a team was created or edited outside these hooks. */
export function useInvalidateTeams(organizationId: string | undefined) {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: teamKeys.list(organizationId) });
}

export function useDeleteTeam(organizationId: string | undefined) {
  const invalidateTeams = useInvalidateTeams(organizationId);
  return useMutation({
    mutationFn: (teamId: string) => axiosInstance.delete(`organization/${organizationId}/team/${teamId}`),
    onSuccess: invalidateTeams,
  });
}
