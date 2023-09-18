# port-forceward
Simple app to force kubectl port-forward.
It will try to `kubectl port-forward` until it succeeds.
It does not have autocompletion but it will try to suggest how to find the resource you were looking for.

# Usage

Download the `port-forceward` and give it execution permissions. Then type
```
./port-forceward --help
```
You will get this output
```
                      __        ____                                            __
    ____  ____  _____/ /_      / __/___  _____________ _      ______ __________/ /
   / __ \/ __ \/ ___/ __/_____/ /_/ __ \/ ___/ ___/ _ \ | /| / / __ `/ ___/ __  /
  / /_/ / /_/ / /  / /_/_____/ __/ /_/ / /  / /__/  __/ |/ |/ / /_/ / /  / /_/ /
 / .___/\____/_/   \__/     /_/  \____/_/   \___/\___/|__/|__/\__,_/_/   \__,_/
/_/


port-forceward v0.1.0 -- A forceful port forwarder for kubectl

USAGE

  $ forceward [(-t, --resource-type, --type text)] (-r, --resource-name, --res, --resource text) (-p, --ports, --port text) [--kubeconfig text]

OPTIONS

  (-t, --resource-type, --type text)
    A user-defined piece of text.

    The resource type to forward

    This setting is optional. Default: 'pod'.

  (-r, --resource-name, --res, --resource text)
    A user-defined piece of text.

    The resource name to forward

  (-p, --ports, --port text)
    A user-defined piece of text.

    The ports to forward

  --kubeconfig text
    A user-defined piece of text.

    The path to the kubeconfig file

    This setting is optional. Default: 'None'.
```
For example:
```
./port-forceward -t svc -r kafka-ui -p 3001:80 --kubeconfig /Users/gurghet/Downloads/myCluster.yaml
```
