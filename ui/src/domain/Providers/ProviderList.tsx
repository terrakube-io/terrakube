import { CloudServerOutlined, ExportOutlined } from "@ant-design/icons";
import { Link, useParams } from "react-router-dom";
import { parseProviderDescription } from "../Modules/registryHelpers";
import { FlatProvider } from "./types";
import "../Modules/Module.css";

type Params = {
  orgid: string;
};

type Props = {
  providers: FlatProvider[];
};

export const ProviderList = ({ providers }: Props) => {
  const { orgid } = useParams<Params>();

  return (
    <ul className="registry-cards">
      {providers.map((item) => {
        const { text, source } = parseProviderDescription(item.description);
        return (
          <li key={item.id} className="registry-card">
            <span className="registry-card-icon" aria-hidden="true">
              <CloudServerOutlined />
            </span>
            <div className="registry-card-body">
              <Link to={`/organizations/${orgid}/registry/providers/${item.id}`} className="registry-card-name">
                {item.name}
              </Link>
              {text && <span className="registry-card-description">{text}</span>}
              <span className="registry-card-meta">
                {item.registryNamespace && <span>By {item.registryNamespace}</span>}
                {item.latestVersion && (
                  <span>
                    Version <code>{item.latestVersion}</code>
                  </span>
                )}
                {source && (
                  <a href={source.url} target="_blank" rel="noopener noreferrer">
                    {source.label} <ExportOutlined aria-label="opens in a new tab" />
                  </a>
                )}
              </span>
            </div>
          </li>
        );
      })}
    </ul>
  );
};

export default ProviderList;
