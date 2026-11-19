import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import axiosInstance from "@/config/axiosConfig";
import type { Team } from "@/domain/types";

/**
 * Query keys for an organization's teams. Keys for data that belongs to an organization start with
 * `["organization", orgId, ...]`, so one organization's data can be invalidated or dropped as a whole.
 * Invalidate `teamKeys.list(orgId)` after any change to the teams.
 */
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

type DeleteTeamCallbacks = {
  onSuccess?: () => void;
  onError?: (error: Error) => void;
};

/**
 * Deletes a team and refetches the list. Report the outcome through `callbacks`, not through the options of
 * `mutate()`: the editor navigates to the list right after asking for a delete, which unmounts the page, and
 * `mutate()` callbacks are skipped once their component is gone. These run either way.
 */
export function useDeleteTeam(organizationId: string | undefined, callbacks: DeleteTeamCallbacks = {}) {
  const invalidateTeams = useInvalidateTeams(organizationId);
  return useMutation({
    mutationFn: (teamId: string) => axiosInstance.delete(`organization/${organizationId}/team/${teamId}`),
    onSuccess: () => {
      callbacks.onSuccess?.();
      return invalidateTeams();
    },
    onError: callbacks.onError,
  });
}
