(function () {
    const VIEWBOX_WIDTH = 1200;
    const VIEWBOX_HEIGHT = 680;
    const CENTER = { x: VIEWBOX_WIDTH / 2, y: VIEWBOX_HEIGHT / 2 };

    function toNumber(value) {
        const number = Number(value);
        return Number.isFinite(number) ? number : null;
    }

    function getNodes() {
        return Array.from(document.querySelectorAll('.topology-node-data')).map((element) => ({
            id: toNumber(element.dataset.id),
            name: element.dataset.name || 'node',
            ip: element.dataset.ip || '-',
            status: element.dataset.status || 'unknown',
            online: element.dataset.online === 'true',
            previousID: toNumber(element.dataset.previousId),
            nextID: toNumber(element.dataset.nextId),
            selected: element.dataset.selected === 'true'
        })).filter((node) => node.id !== null);
    }

    function orderByRing(nodes, selectedId) {
        if (nodes.length <= 1) {
            return nodes;
        }

        const byId = new Map(nodes.map((node) => [node.id, node]));
        const start = byId.get(selectedId) || nodes.slice().sort((a, b) => a.id - b.id)[0];
        const ordered = [];
        const seen = new Set();
        let current = start;

        while (current && !seen.has(current.id)) {
            ordered.push(current);
            seen.add(current.id);
            current = byId.get(current.nextID);
        }

        nodes
            .filter((node) => !seen.has(node.id))
            .sort((a, b) => a.id - b.id)
            .forEach((node) => ordered.push(node));

        return ordered;
    }

    function nodeRadius(node, selectedId, selectedNode) {
        if (node.id === selectedId) {
            return 74;
        }

        if (selectedNode && (node.id === selectedNode.previousID || node.id === selectedNode.nextID)) {
            return 56;
        }

        return 37;
    }

    function calculatePositions(nodes) {
        const positions = new Map();

        if (nodes.length === 1) {
            positions.set(nodes[0].id, { x: CENTER.x, y: CENTER.y });
            return positions;
        }

        const radiusX = 440;
        const radiusY = 235;
        const startAngle = -Math.PI / 2;
        const step = (Math.PI * 2) / nodes.length;

        nodes.forEach((node, index) => {
            const angle = startAngle + step * index;
            positions.set(node.id, {
                x: CENTER.x + Math.cos(angle) * radiusX,
                y: CENTER.y + Math.sin(angle) * radiusY
            });
        });

        return positions;
    }

    function shortenLine(start, end, startRadius, endRadius) {
        const dx = end.x - start.x;
        const dy = end.y - start.y;
        const length = Math.sqrt(dx * dx + dy * dy) || 1;
        const ux = dx / length;
        const uy = dy / length;

        return {
            start: {
                x: start.x + ux * (startRadius + 18),
                y: start.y + uy * (startRadius + 18)
            },
            end: {
                x: end.x - ux * (endRadius + 30),
                y: end.y - uy * (endRadius + 30)
            }
        };
    }

    function curveControlPoint(start, end) {
        const mid = {
            x: (start.x + end.x) / 2,
            y: (start.y + end.y) / 2
        };

        const awayX = mid.x - CENTER.x;
        const awayY = mid.y - CENTER.y;
        const awayLength = Math.sqrt(awayX * awayX + awayY * awayY) || 1;

        // Low curve strength keeps arrows clean and prevents strange distorted arrow shapes.
        const curveStrength = 38;

        return {
            x: mid.x + (awayX / awayLength) * curveStrength,
            y: mid.y + (awayY / awayLength) * curveStrength
        };
    }

    function buildArrowPath(start, control, end) {
        return `M ${start.x.toFixed(2)} ${start.y.toFixed(2)} Q ${control.x.toFixed(2)} ${control.y.toFixed(2)} ${end.x.toFixed(2)} ${end.y.toFixed(2)}`;
    }

    function buildArrowHead(control, end, highlighted) {
        const dx = end.x - control.x;
        const dy = end.y - control.y;
        const length = Math.sqrt(dx * dx + dy * dy) || 1;
        const ux = dx / length;
        const uy = dy / length;

        const size = highlighted ? 18 : 15;
        const halfWidth = highlighted ? 8 : 7;

        const tip = { x: end.x, y: end.y };
        const base = { x: end.x - ux * size, y: end.y - uy * size };

        const left = {
            x: base.x + (-uy) * halfWidth,
            y: base.y + ux * halfWidth
        };

        const right = {
            x: base.x - (-uy) * halfWidth,
            y: base.y - ux * halfWidth
        };

        return `${tip.x.toFixed(2)},${tip.y.toFixed(2)} ${left.x.toFixed(2)},${left.y.toFixed(2)} ${right.x.toFixed(2)},${right.y.toFixed(2)}`;
    }

    function clearSvg(svg) {
        svg.innerHTML = '';
    }

    function renderArrows(svg, nodes, positions, selectedId, selectedNode) {
        const byId = new Map(nodes.map((node) => [node.id, node]));

        nodes.forEach((node) => {
            const target = byId.get(node.nextID);
            if (!target || target.id === node.id) {
                return;
            }

            const start = positions.get(node.id);
            const end = positions.get(target.id);
            if (!start || !end) {
                return;
            }

            const highlighted = node.id === selectedId || target.id === selectedId;
            const shortened = shortenLine(
                start,
                end,
                nodeRadius(node, selectedId, selectedNode),
                nodeRadius(target, selectedId, selectedNode)
            );
            const control = curveControlPoint(shortened.start, shortened.end);

            const arrow = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            arrow.setAttribute('class', highlighted ? 'topology-arrow highlighted' : 'topology-arrow');
            arrow.setAttribute('d', buildArrowPath(shortened.start, control, shortened.end));
            svg.appendChild(arrow);

            const arrowHead = document.createElementNS('http://www.w3.org/2000/svg', 'polygon');
            arrowHead.setAttribute('class', highlighted ? 'topology-arrow-head highlighted' : 'topology-arrow-head');
            arrowHead.setAttribute('points', buildArrowHead(control, shortened.end, highlighted));
            svg.appendChild(arrowHead);
        });
    }

    function renderNodes(layer, nodes, positions, selectedId, selectedNode) {
        layer.innerHTML = '';

        nodes.forEach((node) => {
            const position = positions.get(node.id);
            if (!position) {
                return;
            }

            const dot = document.createElement('button');
            dot.type = 'button';
            dot.className = 'topology-dot';
            dot.style.left = `${(position.x / VIEWBOX_WIDTH) * 100}%`;
            dot.style.top = `${(position.y / VIEWBOX_HEIGHT) * 100}%`;
            dot.setAttribute('title', `${node.name} (${node.id})`);

            if (node.id === selectedId) {
                dot.classList.add('selected');
            } else if (selectedNode && (node.id === selectedNode.previousID || node.id === selectedNode.nextID)) {
                dot.classList.add('neighbour');
            }

            dot.innerHTML = `<span class="dot-name"></span><span class="dot-id"></span>`;
            dot.querySelector('.dot-name').textContent = node.name;
            dot.querySelector('.dot-id').textContent = `ID ${node.id}`;

            dot.addEventListener('click', () => {
                window.location.href = `/dashboard?selectedId=${encodeURIComponent(node.id)}#nodes`;
            });

            layer.appendChild(dot);
        });
    }

    function initTopology() {
        const container = document.getElementById('interactiveTopology');
        const svg = document.getElementById('topologySvg');
        const layer = document.getElementById('topologyNodesLayer');

        if (!container || !svg || !layer) {
            return;
        }

        const nodes = getNodes();
        if (nodes.length === 0) {
            return;
        }

        let selectedId = toNumber(container.dataset.selectedId);
        if (selectedId === null) {
            const markedSelected = nodes.find((node) => node.selected);
            selectedId = markedSelected ? markedSelected.id : nodes[0].id;
        }

        const orderedNodes = orderByRing(nodes, selectedId);
        const selectedNode = nodes.find((node) => node.id === selectedId) || orderedNodes[0];
        const positions = calculatePositions(orderedNodes);

        clearSvg(svg);
        renderArrows(svg, nodes, positions, selectedId, selectedNode);
        renderNodes(layer, nodes, positions, selectedId, selectedNode);
    }

    document.addEventListener('DOMContentLoaded', initTopology);
})();
