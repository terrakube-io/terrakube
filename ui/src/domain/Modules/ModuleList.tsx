import { DownloadOutlined } from "@ant-design/icons";
import { Link, useParams } from "react-router-dom";
import { relativeTime } from "@/modules/utils/dates";
import { formatCount } from "@/modules/utils/formatCount";
import { FlatModule } from "../types";
import { providerIcon } from "./registryHelpers";
import "./Module.css";

type Params = {
  orgid: string;
};

type Props = {
  modules: FlatModule[];
};

export const ModuleList = ({ modules }: Props) => {
  const { orgid } = useParams<Params>();

  return (
    <ul className="registry-cards">
      {modules.map((item) => (
        <li key={item.id} className="registry-card">
          <span className="registry-card-icon" aria-hidden="true">
            {providerIcon(item.provider)}
          </span>
          <div className="registry-card-body">
            <Link to={`/organizations/${orgid}/registry/${item.id}`} className="registry-card-name">
              {item.name}
            </Link>
            {item.description && <span className="registry-card-description">{item.description}</span>}
            <span className="registry-card-meta">
              <span>
                Provider <code>{item.provider}</code>
              </span>
              {item.latestVersion && (
                <span>
                  Version <code>{item.latestVersion}</code>
                </span>
              )}
              <span>
                <DownloadOutlined aria-hidden /> {formatCount(item.downloadQuantity ?? 0)} downloads
              </span>
              {item.updatedDate && <span>Updated {relativeTime(item.updatedDate)}</span>}
            </span>
          </div>
        </li>
      ))}
    </ul>
  );
};
